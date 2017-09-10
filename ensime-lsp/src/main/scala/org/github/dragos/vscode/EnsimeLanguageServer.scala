package org.github.dragos.vscode

import language.postfixOps
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import org.ensime.api._
import org.ensime.api.TypecheckFileReq
import org.ensime.config.EnsimeConfigProtocol
import org.ensime.core._
import org.ensime.util.file._
import org.ensime.util.path._
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import langserver.core.LanguageServer
import langserver.messages._
import langserver.types._
//import scalariform.formatter.preferences.FormattingPreferences
import langserver.core.TextDocument
import org.github.dragos.vscode.ensime.EnsimeActor
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.nio.charset.Charset
import langserver.core.MessageReader

class EnsimeLanguageServer(in: InputStream, out: OutputStream, cwd: Path) extends LanguageServer(in, out) {
  private val system = ActorSystem("ENSIME")
  private var fileStore: TempFileStore = _
  private var ensimeConfig: Option[EnsimeConfig] = None

  // Ensime root actor
  private var ensimeActor: ActorRef = _
  implicit val timeout = Timeout(5 seconds)

  override def start() {
    super.start()
    // if we got here it means the connection was closed
    // cleanup and exit
    shutdown()
  }

  override def initialize(pid: Long, rootPath: String, capabilities: ClientCapabilities): ServerCapabilities = {
    logger.info(s"Initialized with $pid, $rootPath, $capabilities")

    val rootFile = new File(rootPath)
    val cacheDir = new File(rootFile, ".ensime_cache")
    cacheDir.mkdir()
//    val noConfig = EnsimeConfig(
//      rootFile,
//      cacheDir,
//      javaHome = new File(scala.util.Properties.javaHome),
//      name = "scala",
//      scalaVersion = "2.11.8",
//      compilerArgs = Nil,
//      referenceSourceRoots = Nil,
//      subprojects = Nil,
//      formattingPrefs = FormattingPreferences(),
//      sourceMode = false,
//      javaLibs = Nil)

    initializeEnsime(rootPath)

    ServerCapabilities(
      completionProvider = Some(CompletionOptions(false, Seq("."))),
      definitionProvider = true,
      hoverProvider = true,
      documentSymbolProvider = true
    )
  }

  private def initializeEnsime(rootPath: String): Try[EnsimeConfig] = {
    val ensimeFile = new File(s"$rootPath/.ensime")

    val configT = Try {
      EnsimeConfigProtocol.parse(ensimeFile.toPath.readString()(MessageReader.Utf8Charset))
    }

    configT match {
      case Failure(e) =>
        if (ensimeFile.exists)
          connection.showMessage(MessageType.Error, s"Error parsing .ensime: ${e.getMessage}")
        else
          connection.showMessage(MessageType.Error, s"No .ensime file in directory. Run `sbt ensimeConfig` to create one.")
      case Success(config) =>
        //showMessage(MessageType.Info, s"Using configuration: $ensimeFile")
        logger.info(s"Using configuration: $config")
        fileStore = new TempFileStore(config.cacheDir.toString)
        ensimeActor = system.actorOf(Props(classOf[EnsimeActor], this, config), "server")
        ensimeConfig = Some(config)

        // we don't give a damn about them, but Ensime expects it
        ensimeActor ! ConnectionInfoReq
    }
    configT
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit = changes match {
    case FileEvent(uri, FileChangeType.Created | FileChangeType.Changed) +: _ =>
      val rootPath = Try(new File(new URI(uri).toURL.getPath).getParent)
      rootPath.map { path =>
        connection.showMessage(MessageType.Info, ".ensime file change detected. Reloading")
        if (ensimeActor ne null)
          ensimeActor ! ShutdownRequest(".ensime file changed")
        initializeEnsime(path)
      }

    case _ => ()
  }

  type Found = (Path, List[Path])

  // For God's sake, clean this mess some time when I'm not in a rush.
  def findTargetDirectories(sourceFile0: Path): Found = {
    val sourceFile = sourceFile0.toAbsolutePath
    ensimeConfig.map { c =>
      val sourcesToProjects = c.projects.flatMap(p => p.sources.map(s => s -> p))
      val candidateTuple = sourcesToProjects.maxBy {
        case (sourceDir, project) =>
          val absoluteSourceDir = sourceDir.toPath.toAbsolutePath
          if (sourceFile.startsWith(absoluteSourceDir))
            sourceFile.toString.length - absoluteSourceDir.toString.length
          else 0
      }
      logger.info(s"Candidate tuple is $candidateTuple")
      candidateTuple._1.toPath -> candidateTuple._2.targets.map(_.toPath).toList
    }.getOrElse(sys.error("Fatal error: ensime configuration is missing."))
  }

  import com.google.protobuf.timestamp.Timestamp
  // import com.google.protobuf.duration.Duration
  import ch.epfl.scala.profiledb.{ProfileDb, ProfileDbPath}
  import ch.epfl.scala.profiledb.utils.{AbsolutePath, RelativePath}
  import ch.epfl.scala.profiledb.{profiledb => schema}
  final val workingDirectory = AbsolutePath(cwd)

  def loadProfileDbNotesFor(sourceFile: Path): List[Note] = {
    // FIXME(jvican): All this mess is horrendous. Fix soon.
    logger.info(s"Requesting profiledb for $sourceFile")
    logger.info(s"The relative target is ${sourceFile.relativize(cwd).toAbsolutePath}")
    val (sourceDir, targetDirectories) = findTargetDirectories(sourceFile)
    logger.info(s"Candidate target directories $targetDirectories")
    val relativeSourcePath = AbsolutePath(sourceFile).toRelative(workingDirectory)
    logger.info(s"Relative source path is $relativeSourcePath")
    val relativeTargetPath = ProfileDbPath.toProfileDbPath(relativeSourcePath)
    logger.info(s"Relative target path is $relativeTargetPath")
    targetDirectories.map(AbsolutePath.apply).flatMap { targetDir =>
      val dbPath = ProfileDbPath(targetDir, relativeTargetPath)
      logger.info(s"Profiledb path is ${dbPath.target}")
      val readDatabase = ProfileDb.read(dbPath)
      logger.info(s"Read database is $readDatabase")
      implicit val timestampOrdering: Ordering[Timestamp] = Ordering.by(t => t.seconds -> t.nanos)
      readDatabase.map { db =>
        val latestEntry =
          if (db.`type`.isGlobal) sys.error("Database was global.")
          else db.entries.maxBy(e => e.timestamp.getOrElse(Timestamp.defaultInstance))
        val unitProfile = latestEntry.compilationUnitProfile.get
        val implicitNotes = unitProfile.implicitSearchProfiles.toList.map { profile =>
          val msg = s"Triggered ${profile.searches} implicit searches"
          val pos = profile.position.get
          val beg, end = pos.point
          Note(sourceFile.toFile.getAbsolutePath, msg, NoteWarn, beg, end, pos.line, pos.column)
        }
        val macroNotes = unitProfile.macroProfiles.toList.map { profile =>
          val msg = s"Expanded ${profile.expandedMacros} macros of ${profile.approximateSize} nodes during ${profile.duration.get}"
          val pos = profile.position.get
          val beg, end = pos.point
          Note(sourceFile.toFile.getAbsolutePath, msg, NoteWarn, beg, end, pos.line, pos.column)
        }
        val notes = implicitNotes ++ macroNotes
        logger.info(s"Read the following notes: $notes")
        notes
      }.getOrElse {
        logger.info(s"An error occurred $readDatabase")
        Nil
      }
    }
  }

  import scala.collection.mutable.HashSet
  final val pendingDbNotesFor = HashSet[Path]()

  override def onOpenTextDocument(td: TextDocumentItem): Unit = {
    if (ensimeActor eq null) return
    val uri = new URI(td.uri)
    if (uri.getScheme != "file") {
      logger.info(s"Non-file URI in openTextDocument: ${td.uri}")
      return
    }
    val f = new File(new URI(td.uri))
    if (f.getAbsolutePath.startsWith(fileStore.path)) {
      logger.debug(s"Not adding temporary file $f to Ensime")
    } else {
      val filepath = f.toPath
      pendingDbNotesFor += filepath
      ensimeActor ! TypecheckFileReq(SourceFileInfo(RawFile(filepath), Some(td.text)))
    }
  }

  def getPathFrom(td: VersionedTextDocumentIdentifier): Path =
    Paths.get(new URI(td.uri))
  def getPathFrom(td: TextDocumentIdentifier): Path =
    Paths.get(new URI(td.uri))

  override def onChangeTextDocument(td: VersionedTextDocumentIdentifier, changes: Seq[TextDocumentContentChangeEvent]) = {
    // we assume full text sync
    assert(changes.size == 1)
    val change = changes.head
    assert(change.range.isEmpty)
    assert(change.rangeLength.isEmpty)

    val filepath = getPathFrom(td)
    val sourceFileInfo = toSourceFileInfo(filepath, Some(change.text))
    pendingDbNotesFor += filepath
    ensimeActor ! TypecheckFileReq(sourceFileInfo)
  }

  override def onSaveTextDocument(td: TextDocumentIdentifier) = {
    logger.debug(s"saveTextDocuemnt $td")
  }

  override def onCloseTextDocument(td: TextDocumentIdentifier) = {
    logger.debug("Removing ${td.uri} from Ensime.")
    val doc = documentManager.documentForUri(td.uri)
    doc.map(d => ensimeActor ! RemoveFileReq(d.toFile))
  }

  def publishDiagnostics(diagnostics: List[Note]) = {
    val profileDbDiagnostics = pendingDbNotesFor.iterator.flatMap(loadProfileDbNotesFor).toList
    val allDiagnostics = diagnostics ++ profileDbDiagnostics
    val byFile = allDiagnostics.groupBy(_.file)
    pendingDbNotesFor.clear()

    logger.info(s"Received ${diagnostics.size} notes.")

    for {
      doc <- documentManager.allOpenDocuments
      path = Paths.get(new URI(doc.uri)).toString
    } connection.publishDiagnostics(doc.uri, byFile.get(path).toList.flatten.map(toDiagnostic))
  }

  override def shutdown() {
    logger.info("Shutdown request")
//    ensimeActor ! ShutdownRequest("Requested by client")
    system.terminate()
    logger.info("Shutting down actor system.")
    Await.result(system.whenTerminated, Duration.Inf)
    logger.info("Actor system down.")
  }

  override def completionRequest(textDocument: TextDocumentIdentifier, position: Position): ResultResponse = {
    import scala.concurrent.ExecutionContext.Implicits._

    val res = for (doc <- documentManager.documentForUri(textDocument.uri)) yield {
      val future = ensimeActor ? CompletionsReq(
        toSourceFileInfo(getPathFrom(textDocument), Some(new String(doc.contents))),
        doc.positionToOffset(position),
        100, caseSens = false, reload = false)

      future.onComplete { f => logger.debug(s"Completions future completed: succes? ${f.isSuccess}") }

      future.map {
        case CompletionInfoList(prefix, completions) =>
          logger.debug(s"Received ${completions.size} completions: ${completions.take(10).map(_.name)}")
          completions.sortBy(- _.relevance).map(toCompletion)
      }
    }

    res.map(f => CompletionList(false, Await.result(f, 5 seconds))) getOrElse CompletionList(false, Nil)
  }

  override def gotoDefinitionRequest(textDocument: TextDocumentIdentifier, position: Position): Seq[Location] = {
    import scala.concurrent.ExecutionContext.Implicits._
    logger.info(s"Got goto definition request at (${position.line}, ${position.character}).")

    val res = for (doc <- documentManager.documentForUri(textDocument.uri)) yield {
      val future = ensimeActor ? SymbolAtPointReq(
        Right(toSourceFileInfo(getPathFrom(textDocument), Some(new String(doc.contents)))),
        doc.positionToOffset(position))

      future.onComplete { f => logger.debug(s"Goto Definition future completed: succes? ${f.isSuccess}") }

      future.map {
        case SymbolInfo(name, localName, declPos, typeInfo) =>
          declPos.toSeq.flatMap {
            case OffsetSourcePosition(ensimeFile, offset) =>
              fileStore.getFile(ensimeFile).map { path =>
                val file = path.toFile
                val uri = file.toURI.toString
                val doc = TextDocument(uri, file.readString()(MessageReader.Utf8Charset).toCharArray())
                val start = doc.offsetToPosition(offset)
                val end = start.copy(character = start.character + localName.length())

                logger.info(s"Found definition at $uri, line: ${start.line}")
                Seq(Location(uri, Range(start, end)))
              }.recover {
                case e =>
                  logger.error(s"Couldn't retrieve hyperlink target file $e")
                  Seq()
              }.get

            case _ =>
              Seq()
          }
      }
    }

    res.map { f =>  Await.result(f, 5 seconds) } getOrElse Seq.empty[Location]
  }

  override def hoverRequest(textDocument: TextDocumentIdentifier, position: Position): Hover = {
    import scala.concurrent.ExecutionContext.Implicits._
    logger.info(s"Got hover request at (${position.line}, ${position.character}).")

    // val res = for (doc <- documentManager.documentForUri(textDocument.uri)) yield {
    //   val pos = doc.positionToOffset(position)
    //   val future = ensimeActor ? TypeAtPointReq(
    //     Right(toSourceFileInfo(textDocument.uri, Some(new String(doc.contents)))),
    //     OffsetRange(pos, pos))

    //   future.onComplete { f => logger.debug(s"Goto Definition future completed: succes? ${f.isSuccess}") }

    //   future.map {
    //     case typeInfo: TypeInfo =>
    //       logger.info(s"Retrieved typeInfo $typeInfo")
    //       Hover(Seq(RawMarkedString("scala", typeInfo.toString)), Some(Range(position, position)))
    //   }
    // }
    val res = for (doc <- documentManager.documentForUri(textDocument.uri)) yield {
      val future = ensimeActor ? DocUriAtPointReq(
        Right(toSourceFileInfo(getPathFrom(textDocument), Some(new String(doc.contents)))),
        OffsetRange(doc.positionToOffset(position)))

      future.onComplete { f => logger.debug(s"DocUriAtPointReq future completed: succes? ${f.isSuccess}") }

      future.map {
        case Some(sigPair @ DocSigPair(DocSig(_, scalaSig), DocSig(_, javaSig))) =>
          val sig = scalaSig.orElse(javaSig).getOrElse("")
          logger.info(s"Retrieved signature $sig from @sigPair")
          Hover(Seq(RawMarkedString("scala", sig)), Some(Range(position, position)))
      }
    }
    res.map { f =>  Await.result(f, 5 seconds) } getOrElse Hover(Nil, None)
  }

  override def documentSymbols(tdi: TextDocumentIdentifier): Seq[SymbolInformation] = {
    import scala.concurrent.ExecutionContext.Implicits._
    import scala.concurrent.Future

    if (ensimeActor ne null) {
      val res: Option[Future[List[SymbolInformation]]] = for (doc <- documentManager.documentForUri(tdi.uri)) yield {

        def toSymbolInformation(structure: StructureViewMember, outer: Option[String]): Seq[SymbolInformation] = {
          structure match {
            case StructureViewMember(keyword, name, pos, members) =>
              val kind = keywordToKind.getOrElse(keyword, SymbolKind.Field)
              val rest = members.flatMap(m => toSymbolInformation(m, Some(name)))
              val position = pos match {
                case OffsetSourcePosition(_, offset) => doc.offsetToPosition(offset)
                case LineSourcePosition(_, line) => Position(line, 0)
                case _ =>
                  logger.error(s"Unknown position for $name: $pos")
                  Position(0, 0)
              }

              SymbolInformation(
                name,
                kind,
                Location(tdi.uri, Range(position, position.copy(character = position.character + name.length))),
                outer) +: rest

            case _ =>
              logger.error(s"Unknown structure element: $structure")
              Seq.empty
          }
        }

        logger.info(s"Document Symbols request for ${tdi.uri}")
        val future = ensimeActor ? StructureViewReq(toSourceFileInfo(getPathFrom(tdi), Some(new String(doc.contents))))

        future.onComplete { f => logger.debug(s"StructureView future completed: succes? ${f.isSuccess}") }
        future.map {
          case StructureView(members) =>
            logger.debug(s"got back: $members")
            members.flatMap(m => toSymbolInformation(m, None))
        }
      }
      res.map { f =>  Await.result(f, 5 seconds) } getOrElse Seq.empty
    } else Seq.empty
  }

  private val keywordToKind = Map(
    "class" -> SymbolKind.Class,
    "trait" -> SymbolKind.Interface,
    "type" -> SymbolKind.Interface,
    "package" -> SymbolKind.Package,
    "def" -> SymbolKind.Method,
    "val" -> SymbolKind.Constant,
    "var" -> SymbolKind.Field
  )


  private def toSourceFileInfo(path: Path, contents: Option[String] = None): SourceFileInfo =
    SourceFileInfo(RawFile(path), contents)

  private def toCompletion(completionInfo: CompletionInfo) = {
    def symKind: Option[Int] = completionInfo.typeInfo map { info =>
      info.declaredAs match {
        case DeclaredAs.Method => CompletionItemKind.Method
        case DeclaredAs.Class  => CompletionItemKind.Class
        case DeclaredAs.Field  => CompletionItemKind.Field
        case DeclaredAs.Interface | DeclaredAs.Trait => CompletionItemKind.Interface
        case DeclaredAs.Object    => CompletionItemKind.Module
        case _ => CompletionItemKind.Value
      }
    }

    CompletionItem(
      label = completionInfo.name,
      kind = symKind,
      detail = completionInfo.typeInfo.map(_.fullName)
    )
  }

  private def toDiagnostic(note: Note): Diagnostic = {
    val start: Int = note.beg
    val end: Int = note.end
    val length = end - start

    val severity = note.severity match {
      case NoteError => DiagnosticSeverity.Error
      case NoteWarn => DiagnosticSeverity.Warning
      case NoteInfo => DiagnosticSeverity.Information
    }

    // Scalac reports 1-based line and columns, while Code expects 0-based
    val range = Range(Position(note.line - 1, note.col - 1), Position(note.line - 1, note.col - 1 + length))

    Diagnostic(range, Some(severity), code = None, source = Some("Scala"), message = note.msg)
  }
}
