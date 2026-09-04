package ba.sake.codeps.cli

import ba.sake.codeps.graph.{Aggregator, TestFilter}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.model.{CollapseRule, DepsGraph, ExportGraph}
import ba.sake.codeps.report.{CycleInspection, HealthHistory, HealthHistoryHtml, HealthRecordingDecision, HealthSnapshot, MetricsCalculator, NodeInspection, ReportInspector}
import ba.sake.codeps.semanticdb.SemanticDbParser
import ba.sake.tupson.{*, given}
import mainargs.{arg, main, ParserForMethods, TokensReader}
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.time.{Duration, Instant}
import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}
import scala.jdk.CollectionConverters.*

/** One configured project flows through parsing, analysis, history, and HTML
  * rendering. The individual stages are intentionally implementation details. */
object Main:

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(args: Array[String]): Int =
    val parser = ParserForMethods(this)
    if args.headOption.contains("--version") then
      println(version)
      0
    else if args.contains("--help") then
      println(parser.helpText())
      0
    else parser.runEither(args.toSeq) match
      case Left(err)        => System.err.println(err); 1
      case Right(code: Int) => code
      case Right(_)         => 0

  private def version = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  enum ReportFormat:
    case Json, Table
  given TokensReader.Simple[ReportFormat] with
    def shortName = "format"
    def read(values: Seq[String]) = values match
      case Seq("json")  => Right(ReportFormat.Json)
      case Seq("table") => Right(ReportFormat.Table)
      case Seq(value)    => Left(s"unknown format: $value (expected json or table)")
      case _             => Left("expected exactly one format")

  private enum SourceKind:
    case Semanticdb, Jdeps, Export
  private enum Scope:
    case Packages, Files
    def metrics = this match
      case Packages => MetricsCalculator.Scope.Packages
      case Files    => MetricsCalculator.Scope.Files
  private case class Project(
      id: String, root: os.Path, source: SourceKind, inputs: Seq[String], scope: Scope,
      include: Seq[String], exclude: Seq[String], collapse: Seq[String], skipTests: Boolean,
      testPatterns: Seq[String], significance: Double, maxSnapshotAge: String
  )
  private case class Config(repoRoot: os.Path, projects: Seq[Project])

  @main
  def status(
      @arg(name = "config", doc = "Repository config; defaults to .codeps/config.yaml") config: Option[String] = None,
      @arg(name = "project", doc = "Configured project id; repeat to run a subset") projects: Seq[String] = Nil,
      @arg(name = "commit") commit: Option[String] = None,
      @arg(name = "generated-at") generatedAt: Option[String] = None,
      @arg(name = "out", doc = "HTML output path; only valid when one project is selected") out: Option[String] = None
  ): Int =
    val result = for
      loaded <- loadConfig(config)
      selected <- selectProjects(loaded, projects)
      _ <- if out.nonEmpty && selected.size != 1 then Left("--out requires exactly one selected project") else Right(())
      _ <- sequence(selected.map(runStatusProject(loaded.repoRoot, _, commit, generatedAt, out)))
    yield ()
    result.fold(fail, _ => 0)

  @main
  def inspectCycle(
      @arg(name = "id") id: String,
      @arg(name = "project") project: Option[String] = None,
      @arg(name = "config") config: Option[String] = None,
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table
  ): Int = inspect(project, config, id, format, ReportInspector.inspectCycle(_, id))

  @main
  def inspectNode(
      @arg(name = "id") id: String,
      @arg(name = "project") project: Option[String] = None,
      @arg(name = "config") config: Option[String] = None,
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table
  ): Int = inspect(project, config, id, format, ReportInspector.inspectNode(_, id))

  private def inspect(
      requested: Option[String], configPath: Option[String], id: String, format: ReportFormat,
      action: ba.sake.codeps.report.MetricsReport => Either[String, Any]
  ): Int =
    val result = for
      config <- loadConfig(configPath)
      project <- selectProject(config, requested)
      detail <- readReportInput(reportPath(config.repoRoot, project)).flatMap(action)
    yield print(detail match
      case cycle: CycleInspection => format match
        case ReportFormat.Json => ReportInspector.renderJson(cycle)
        case ReportFormat.Table => ReportInspector.renderTable(cycle)
      case node: NodeInspection => format match
        case ReportFormat.Json => ReportInspector.renderJson(node)
        case ReportFormat.Table => ReportInspector.renderTable(node))
    result.fold(fail, _ => 0)

  private def runStatusProject(
      repoRoot: os.Path, project: Project, explicitCommit: Option[String], rawGeneratedAt: Option[String], explicitOut: Option[String]
  ): Either[String, Unit] =
    for
      exportGraph <- readSource(project)
      patterns <- testPatternsOrError(project.skipTests, project.testPatterns)
      rules <- parseRules(project.collapse)
      report <- MetricsCalculator.run(graphFor(project.scope.metrics, exportGraph, project.skipTests), project.scope.metrics,
        project.include, project.exclude, rules, patterns)
      dated <- setGeneratedAt(report, rawGeneratedAt)
      commit <- resolvedCommit(explicitCommit, repoRoot)
      age <- parseMaxSnapshotAge(project.maxSnapshotAge)
      _ <- recordHistory(historyPath(repoRoot, project), HealthSnapshot.fromReport(dated, commit), project.significance, age)
      snapshots <- readHistory(historyPath(repoRoot, project)).map(_._3)
    yield
      writeFile(reportPath(repoRoot, project), dated.toJson(spaces = 2, sort = true))
      val html = explicitOut.map(path => os.Path(path, os.pwd)).getOrElse(htmlPath(repoRoot, project))
      writeFile(html, HealthHistoryHtml.render(snapshots))
      println(s"${project.id}: ${html.relativeTo(repoRoot)}")

  private def readSource(project: Project): Either[String, ExportGraph] =
    val inputs = project.inputs.map(os.Path(_, project.root))
    if inputs.isEmpty then Left(s"project '${project.id}' needs at least one input")
    else if inputs.exists(!os.exists(_)) then Left(s"input path does not exist: ${inputs.find(!os.exists(_)).get}")
    else project.source match
      case SourceKind.Export =>
        if inputs.size != 1 || !os.isFile(inputs.head) then Left("export source needs exactly one input file")
        else readGraphInput(inputs.head)
      case SourceKind.Jdeps =>
        if inputs.exists(!os.isFile(_)) then Left("jdeps inputs must be files")
        else Right(Aggregator.toExport(inputs.foldLeft(DepsGraph.empty)((deps, file) => deps.merge(JdepsParser.parse(os.read(file))))))
      case SourceKind.Semanticdb =>
        if inputs.exists(!os.isDir(_)) then Left("semanticdb inputs must be directories")
        else
          val files = inputs.flatMap(dir => os.walk(dir).filter(_.ext == "semanticdb").toSeq)
          if files.isEmpty then Left("no .semanticdb files found")
          else
            var deps = DepsGraph.empty
            for file <- files do SemanticDbParser.parse(os.read.bytes(file), project.root.toNIO) match
              case Right(parsed) => deps = deps.merge(parsed)
              case Left(err)     => System.err.println(s"warning: $err")
            Right(Aggregator.toExport(deps.withoutDanglingEdges))

  private def recordHistory(path: os.Path, current: HealthSnapshot, significance: Double, age: Option[FiniteDuration]): Either[String, Unit] =
    if significance < 0 || !significance.isFinite then Left("significance must be a non-negative finite decimal")
    else for
      existing <- readHistory(path)
      due <- checkpointDue(existing._3.lastOption, current.at, age)
    yield HealthHistory.decision(existing._3.lastOption, current, significance, due) match
      case HealthRecordingDecision.NotSignificant => ()
      case _ => writeFile(path, existing._2 + current.toJson(spaces = 0, sort = true) + "\n")

  private def loadConfig(explicit: Option[String]): Either[String, Config] =
    val repoRoot = findRepoRoot()
    val path = explicit.map(os.Path(_, os.pwd)).getOrElse(repoRoot / ".codeps" / "config.yaml")
    if !os.exists(path) && explicit.isEmpty then
      writeFile(path, starterConfig)
      println(s"created starter config: ${path.relativeTo(repoRoot)}")
      parseConfig(repoRoot, starterConfig)
    else if !os.exists(path) then Left(s"config path does not exist: $path")
    else if !os.isFile(path) then Left(s"config path is not a file: $path")
    else try parseConfig(repoRoot, os.read(path))
      catch case error: Exception => Left(s"invalid config: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")

  private val starterConfig = """# codeps analyzes this repository as one project by default.
projects:
  root:
    root: .
    source: semanticdb
    inputs: [.]
    scope: packages
"""

  private def parseConfig(repoRoot: os.Path, raw: String): Either[String, Config] =
    val loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load[Any](raw)
    for
      root <- mapOf(loaded, "config must be a YAML mapping")
      projectValues <- mapOf(root.getOrElse("projects", null), "config needs a 'projects' mapping")
      projects <- sequence(projectValues.toSeq.map { case (id, rawProject) => mapOf(rawProject, s"project '$id' must be a mapping").flatMap(parseProject(repoRoot, id, _)) })
    yield Config(repoRoot, projects.sortBy(_.id))

  private def parseProject(repoRoot: os.Path, id: String, values: Map[String, Any]): Either[String, Project] =
    for
      inputs <- strings(values.get("inputs")).filter(_.nonEmpty).toRight(s"project '$id' needs non-empty 'inputs'")
      source <- sourceKind(string(values.get("source")).getOrElse("semanticdb"), id)
      scope <- scopeOf(string(values.get("scope")).getOrElse("packages"), id)
      significance <- decimal(values.get("significance")).getOrElse(Right(0.01))
      _ <- if significance >= 0 && significance.isFinite then Right(()) else Left(s"project '$id' significance must be non-negative")
    yield Project(id, os.Path(string(values.get("root")).getOrElse("."), repoRoot), source, inputs, scope,
      strings(values.get("include")).getOrElse(Nil), strings(values.get("exclude")).getOrElse(Nil), strings(values.get("collapse")).getOrElse(Nil),
      bool(values.get("skip-tests")).getOrElse(false), strings(values.get("test-pattern")).getOrElse(Nil), significance,
      string(values.get("max-snapshot-age")).getOrElse("7d"))

  private def mapOf(value: Any, error: String): Either[String, Map[String, Any]] = value match
    case map: java.util.Map[?, ?] => Right(map.asScala.toSeq.collect { case (key: String, item) => key -> item }.toMap)
    case _ => Left(error)
  private def string(value: Option[Any]) = value.collect { case item: String => item }
  private def strings(value: Option[Any]): Option[Seq[String]] = value match
    case None => Some(Nil)
    case Some(item: String) => Some(Seq(item))
    case Some(items: java.util.List[?]) =>
      val strings = items.asScala.toSeq.collect { case item: String => item }
      if strings.size == items.size then Some(strings) else None
    case _ => None
  private def bool(value: Option[Any]) = value.collect { case item: java.lang.Boolean => item.booleanValue }
  private def decimal(value: Option[Any]): Option[Either[String, Double]] = value.map {
    case item: java.lang.Number => Right(item.doubleValue)
    case _ => Left("significance must be a decimal")
  }
  private def sourceKind(value: String, id: String): Either[String, SourceKind] = value match
    case "semanticdb" => Right(SourceKind.Semanticdb)
    case "jdeps" => Right(SourceKind.Jdeps)
    case "export" => Right(SourceKind.Export)
    case other => Left(s"project '$id' source must be semanticdb, jdeps, or export (received: $other)")
  private def scopeOf(value: String, id: String): Either[String, Scope] = value match
    case "packages" => Right(Scope.Packages)
    case "files" => Right(Scope.Files)
    case other => Left(s"project '$id' scope must be packages or files (received: $other)")

  private def selectProjects(config: Config, requested: Seq[String]) =
    val unknown = requested.distinct.filterNot(id => config.projects.exists(_.id == id))
    if unknown.nonEmpty then Left(s"unknown project: ${unknown.mkString(", ")}")
    else Right(if requested.isEmpty then config.projects else config.projects.filter(p => requested.contains(p.id)))
  private def selectProject(config: Config, requested: Option[String]) = requested match
    case Some(id) => config.projects.find(_.id == id).toRight(s"unknown project: $id")
    case None if config.projects.size == 1 => Right(config.projects.head)
    case None => Left("--project is required when config defines more than one project")
  private def findRepoRoot() =
    val result = os.proc("git", "rev-parse", "--show-toplevel").call(cwd = os.pwd, check = false, stderr = os.Pipe)
    if result.exitCode == 0 then os.Path(result.out.text().trim) else os.pwd
  private def reportPath(root: os.Path, project: Project) = root / ".codeps" / "out" / project.id / "report.json"
  private def htmlPath(root: os.Path, project: Project) = root / ".codeps" / "out" / project.id / "index.html"
  private def historyPath(root: os.Path, project: Project) = root / ".codeps" / s"${project.id}.ndjson"

  private def setGeneratedAt(report: ba.sake.codeps.report.MetricsReport, value: Option[String]) = value match
    case None => Right(report)
    case Some(raw) => try Right(report.copy(generatedAt = Instant.parse(raw).toString))
      catch case _: Exception => Left(s"invalid --generated-at: $raw (expected ISO-8601 UTC instant)")
  private def resolvedCommit(explicit: Option[String], root: os.Path): Either[String, String] = explicit match
    case Some(value) if value.nonEmpty => Right(value)
    case Some(_) => Left("--commit must not be empty")
    case None =>
      val result = os.proc("git", "rev-parse", "HEAD").call(cwd = root, check = false, stderr = os.Pipe)
      if result.exitCode == 0 then Right(result.out.text().trim) else Left("unable to resolve git HEAD; pass --commit explicitly")
  private def readHistory(path: os.Path): Either[String, (os.Path, String, Seq[HealthSnapshot])] =
    if os.exists(path) && !os.isFile(path) then Left(s"history path is not a file: $path")
    else
      val raw = if os.exists(path) then os.read(path) else ""
      HealthHistory.parseNdjson(raw).map(snapshots => (path, raw, snapshots))
  private def checkpointDue(last: Option[HealthSnapshot], currentAt: String, age: Option[FiniteDuration]) = (last, age) match
    case (_, None) | (None, _) => Right(false)
    case (Some(previous), Some(max)) => try Right(Duration.between(Instant.parse(previous.at), Instant.parse(currentAt)).toMillis >= max.toMillis)
      catch case _: Exception => Left("history timestamp is invalid")
  private def parseMaxSnapshotAge(raw: String): Either[String, Option[FiniteDuration]] = if raw == "off" then Right(None) else parseDuration(raw).map(Some(_))
  private def parseDuration(raw: String): Either[String, FiniteDuration] =
    val pattern = "^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|d)$".r
    raw match
      case pattern(amount, unit) =>
        val factor = unit match
          case "ms" => BigDecimal(1000000); case "s" => BigDecimal(1000000000)
          case "m" => BigDecimal(60000000000L); case "d" => BigDecimal(86400000000000L)
        try
          val nanos = (BigDecimal(amount) * factor).toLongExact
          if nanos > 0 then Right(FiniteDuration(nanos, NANOSECONDS)) else Left(s"max-snapshot-age must be positive: $raw")
        catch case _: ArithmeticException => Left(s"invalid max-snapshot-age: $raw")
      case _ => Left(s"invalid max-snapshot-age: $raw (expected 7d, 1s, or off)")
  private def graphFor(scope: MetricsCalculator.Scope, exportGraph: ExportGraph, skipTests: Boolean): DepsGraph = scope match
    case MetricsCalculator.Scope.Packages if skipTests && exportGraph.files.nodes.nonEmpty => exportGraph.fileDeps
    case MetricsCalculator.Scope.Packages => exportGraph.packageDeps
    case MetricsCalculator.Scope.Files => exportGraph.fileDeps
  private def testPatternsOrError(skipTests: Boolean, patterns: Seq[String]) =
    if patterns.nonEmpty && !skipTests then Left("test-pattern requires skip-tests")
    else Right(if skipTests then Some(if patterns.nonEmpty then patterns else TestFilter.defaultPatterns) else None)
  private def parseRules(collapse: Seq[String]) = collapse.foldLeft(Right(Nil): Either[String, Seq[CollapseRule]]) { (acc, pattern) =>
    for rules <- acc; rule <- CollapseRule.parse(pattern) yield rules :+ rule
  }
  private def readGraphInput(path: os.Path): Either[String, ExportGraph] =
    if !os.exists(path) then Left(s"input path does not exist: $path")
    else if !os.isFile(path) then Left(s"not a file: $path")
    else try Right(os.read(path).parseJson[ExportGraph])
      catch case error: ba.sake.tupson.TupsonException => Left(s"failed to parse json: ${error.getMessage}")
  private def readReportInput(path: os.Path): Either[String, ba.sake.codeps.report.MetricsReport] =
    if !os.exists(path) then Left(s"report path does not exist: $path")
    else if !os.isFile(path) then Left(s"report path is not a file: $path")
    else ReportInspector.parse(os.read(path)).left.map(err => s"failed to parse report json: $err")
  private def writeFile(path: os.Path, content: String): Unit = { os.makeDir.all(path / os.up); os.write.over(path, content) }
  private def sequence[A](items: Seq[Either[String, A]]) = items.foldLeft(Right(Nil): Either[String, Seq[A]])((result, item) => for done <- result; value <- item yield done :+ value)
  private def fail(message: String): Int = { System.err.println(s"error: $message"); 1 }
