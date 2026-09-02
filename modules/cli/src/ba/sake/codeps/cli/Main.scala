package ba.sake.codeps.cli

import ba.sake.codeps.graph.{Aggregator, Collapser, TestFilter}
import ba.sake.codeps.model.{CollapseRule, DepsGraph}
import ba.sake.codeps.report.{CutAnalysisBudget, MetricsCalculator, ReportInspector, ReportMarkdown, ReportTable}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.semanticdb.SemanticDbParser
import ba.sake.tupson.{*, given}
import mainargs.{arg, main, ParserForMethods, TokensReader}

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}
import java.util.Locale

object Main:

  def main(args: Array[String]): Unit = sys.exit(run(args))

  /** Testable entry point: returns the process exit code. */
  def run(args: Array[String]): Int =
    val parser = ParserForMethods(this)
    if args.headOption.contains("--version") then
      println(version)
      0
    else if args.contains("--help") then
      println(parser.helpText())
      0
    else
      parser.runEither(args.toSeq) match
        case Left(err) =>
          System.err.println(err)
          1
        case Right(code: Int) => code
        case Right(_)         => 0 // unreachable: subcommands return Int

  private def version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  enum InputFormat:
    case Semanticdb, Jdeps

  given TokensReader.Simple[InputFormat] with
    def shortName: String = "from"
    def read(strs: Seq[String]): Either[String, InputFormat] =
      strs match
        case Seq("semanticdb") => Right(InputFormat.Semanticdb)
        case Seq("jdeps")      => Right(InputFormat.Jdeps)
        case Seq(other)        => Left(s"unknown input format: $other (expected semanticdb or jdeps)")
        case _                 => Left("expected exactly one input format")

  enum ReportFormat:
    case Json, Table, Markdown

  given TokensReader.Simple[ReportFormat] with
    def shortName: String = "format"
    def read(strs: Seq[String]): Either[String, ReportFormat] =
      strs match
        case Seq("json")  => Right(ReportFormat.Json)
        case Seq("table") => Right(ReportFormat.Table)
        case Seq("markdown") => Right(ReportFormat.Markdown)
        case Seq(other)   => Left(s"unknown format: $other (expected json, table, or markdown)")
        case _            => Left("expected exactly one format")

  enum ColorMode:
    case Auto, Always, Never

  given TokensReader.Simple[ColorMode] with
    def shortName: String = "color"
    def read(strs: Seq[String]): Either[String, ColorMode] =
      strs match
        case Seq("auto")   => Right(ColorMode.Auto)
        case Seq("always") => Right(ColorMode.Always)
        case Seq("never")  => Right(ColorMode.Never)
        case Seq(other)    => Left(s"unknown color mode: $other (expected auto, always, or never)")
        case _             => Left("expected exactly one color mode")

  given TokensReader.Simple[ReportTable.ColumnGroup] with
    def shortName: String = "columns"
    def read(strs: Seq[String]): Either[String, ReportTable.ColumnGroup] =
      strs match
        case Seq("core")        => Right(ReportTable.ColumnGroup.Core)
        case Seq("visibility")  => Right(ReportTable.ColumnGroup.Visibility)
        case Seq("mutability")  => Right(ReportTable.ColumnGroup.Mutability)
        case Seq("coupling")    => Right(ReportTable.ColumnGroup.Coupling)
        case Seq("all")         => Right(ReportTable.ColumnGroup.All)
        case Seq(other)         => Left(s"unknown columns group: $other (expected core, visibility, mutability, coupling, or all)")
        case _                  => Left("expected exactly one columns group")

  @main
  def `export`(
      @arg(short = 'f', name = "from") from: InputFormat,
      @arg(name = "root") root: Option[String],
      @arg(short = 'o') out: Option[String],
      @arg(short = 'i', name = "input") inputs: Seq[String]
  ): Int =
    if inputs.isEmpty then
      System.err.println("error: at least one input is required")
      1
    else
      val paths = inputs.map(p => os.Path(p, os.pwd))
      paths.find(!os.exists(_)) match
        case Some(missing) =>
          System.err.println(s"error: input path does not exist: $missing")
          1
        case None =>
          from match
            case InputFormat.Semanticdb =>
              paths.find(!os.isDir(_)) match
                case Some(notDir) =>
                  System.err.println(s"error: not a directory: $notDir")
                  1
                case None =>
                  var deps = DepsGraph.empty
                  var parseFailed = false
                  val workspaceRoot = root.map(r => os.Path(r, os.pwd)).getOrElse(os.pwd)
                  val files = paths.flatMap(d => os.walk(d).filter(_.ext == "semanticdb").toSeq)
                  if files.isEmpty then
                    System.err.println("error: no .semanticdb files found")
                    1
                  else
                    for f <- files do
                      SemanticDbParser.parse(os.read.bytes(f), workspaceRoot.toNIO) match
                        case Right(d)  => deps = deps.merge(d)
                        case Left(err) =>
                          parseFailed = true
                          System.err.println(s"warning: $err")
                    // A partial SemanticDB export cannot support complete
                    // public-symbol use or unused-public-symbol claims. Drop
                    // both optional indexes so downstream reports omit them.
                    val exportGraph =
                      if parseFailed then deps.copy(symbolReferences = None, declaredPublicSymbols = None)
                      else deps
                    writeOutput(Aggregator.fileLevel(exportGraph.withoutDanglingEdges).toJson(spaces = 2, sort = true), out)
                    0
            case InputFormat.Jdeps =>
              paths.find(!os.isFile(_)) match
                case Some(notFile) =>
                  System.err.println(s"error: not a file: $notFile")
                  1
                case None =>
                  var deps = DepsGraph.empty
                  for f <- paths do
                    deps = deps.merge(JdepsParser.parse(os.read(f)))
                  writeOutput(Aggregator.fileLevel(deps).toJson(spaces = 2, sort = true), out)
                  0

  private case class ReportOptions(
      format: ReportFormat,
      include: Seq[String],
      exclude: Seq[String],
      collapse: Seq[String],
      skipTests: Boolean,
      testPattern: Seq[String],
      showAll: Boolean,
      columns: Seq[ReportTable.ColumnGroup],
      color: ColorMode,
      analyzeCuts: Boolean,
      cutTimeLimit: Option[String],
      cutCandidateLimit: Option[Int],
      out: Option[String],
      input: String
  )

  private val defaultCutTimeLimit = FiniteDuration(1, "second")
  private val defaultCutCandidateLimit = 10000

  @main
  def reportPackages(
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table,
      @arg(name = "color") color: ColorMode = ColorMode.Auto,
      @arg(name = "include") include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(name = "skip-tests") skipTests: mainargs.Flag,
      @arg(name = "test-pattern") testPattern: Seq[String] = Nil,
      @arg(name = "all") all: mainargs.Flag,
      @arg(name = "columns", doc = "surface columns: core, visibility, mutability, coupling, or all (repeatable)") columns: Seq[ReportTable.ColumnGroup],
      @arg(name = "analyze-cuts") analyzeCuts: mainargs.Flag,
      @arg(name = "cut-time-limit") cutTimeLimit: Option[String],
      @arg(name = "cut-candidate-limit") cutCandidateLimit: Option[Int],
      @arg(short = 'o') out: Option[String],
      @arg(short = 'i', name = "input") input: String
  ): Int = runReport(MetricsCalculator.Scope.Packages, ReportOptions(format, include, exclude, collapse,
    skipTests.value, testPattern, all.value, columns, color, analyzeCuts.value, cutTimeLimit, cutCandidateLimit, out, input))

  @main
  def reportFiles(
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table,
      @arg(name = "color") color: ColorMode = ColorMode.Auto,
      @arg(name = "include") include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(name = "skip-tests") skipTests: mainargs.Flag,
      @arg(name = "test-pattern") testPattern: Seq[String] = Nil,
      @arg(name = "all") all: mainargs.Flag,
      @arg(name = "columns", doc = "surface columns: core, visibility, mutability, coupling, or all (repeatable)") columns: Seq[ReportTable.ColumnGroup],
      @arg(name = "analyze-cuts") analyzeCuts: mainargs.Flag,
      @arg(name = "cut-time-limit") cutTimeLimit: Option[String],
      @arg(name = "cut-candidate-limit") cutCandidateLimit: Option[Int],
      @arg(short = 'o') out: Option[String],
      @arg(short = 'i', name = "input") input: String
  ): Int = runReport(MetricsCalculator.Scope.Files, ReportOptions(format, include, exclude, collapse,
    skipTests.value, testPattern, all.value, columns, color, analyzeCuts.value, cutTimeLimit, cutCandidateLimit, out, input))

  @main
  def inspectCycle(
      @arg(name = "report") report: String,
      @arg(name = "id") id: String,
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table
  ): Int =
    readReportInput(report).flatMap(ReportInspector.inspectCycle(_, id)) match
      case Left(err) =>
        System.err.println(s"error: $err")
        1
      case Right(detail) =>
        val content = format match
          case ReportFormat.Json  => ReportInspector.renderJson(detail)
          case ReportFormat.Table => ReportInspector.renderTable(detail)
          case ReportFormat.Markdown => ReportInspector.renderTable(detail)
        writeOutput(content, None)
        0

  @main
  def inspectNode(
      @arg(name = "report") report: String,
      @arg(name = "id") id: String,
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table
  ): Int =
    readReportInput(report).flatMap(ReportInspector.inspectNode(_, id)) match
      case Left(err) =>
        System.err.println(s"error: $err")
        1
      case Right(detail) =>
        val content = format match
          case ReportFormat.Json  => ReportInspector.renderJson(detail)
          case ReportFormat.Table => ReportInspector.renderTable(detail)
          case ReportFormat.Markdown => ReportInspector.renderTable(detail)
        writeOutput(content, None)
        0

  private def runReport(scope: MetricsCalculator.Scope, options: ReportOptions): Int =
    parseCutBudget(options.analyzeCuts, options.cutTimeLimit, options.cutCandidateLimit) match
      case Left(err) =>
        System.err.println(s"error: $err")
        1
      case Right(cutBudget) =>
        testPatternsOrError(options.skipTests, options.testPattern) match
          case Left(err) =>
            System.err.println(s"error: $err")
            1
          case Right(patterns) =>
            readGraphInput(options.input) match
              case Left(err) =>
                System.err.println(s"error: $err")
                1
              case Right(graph) =>
                parseRules(options.collapse).flatMap { rules =>
                  MetricsCalculator.run(
                    graph,
                    scope,
                    options.include,
                    options.exclude,
                    rules,
                    patterns,
                    cutBudget
                  ).map { metricsReport =>
                    options.format match
                      case ReportFormat.Json  => metricsReport.toJson(spaces = 2, sort = true)
                      case ReportFormat.Table =>
                        ReportTable.render(
                          metricsReport,
                          showAll = options.showAll,
                          columns = options.columns,
                          color = shouldColor(options.format, options.color, options.out)
                        )
                      case ReportFormat.Markdown =>
                        ReportMarkdown.render(
                          metricsReport,
                          showAll = options.showAll,
                          columns = options.columns
                        )
                  }
                } match
                  case Left(err) =>
                    System.err.println(s"error: $err")
                    1
                  case Right(content) =>
                    writeOutput(content, options.out)
                    0

  private def parseCutBudget(
      analyzeCuts: Boolean,
      rawTimeLimit: Option[String],
      rawCandidateLimit: Option[Int]
  ): Either[String, Option[CutAnalysisBudget]] =
    if !analyzeCuts then
      if rawTimeLimit.nonEmpty || rawCandidateLimit.nonEmpty then
        Left("--cut-time-limit and --cut-candidate-limit require --analyze-cuts")
      else Right(None)
    else
      for
        timeLimit <- rawTimeLimit.map(parseDuration).getOrElse(Right(defaultCutTimeLimit))
        candidateLimit <- rawCandidateLimit match
          case None    => Right(defaultCutCandidateLimit)
          case Some(n) => if n > 0 then Right(n) else Left("--cut-candidate-limit must be positive")
      yield Some(CutAnalysisBudget(timeLimit, candidateLimit))

  private def parseDuration(raw: String): Either[String, FiniteDuration] =
    val pattern = "^([0-9]+(?:\\.[0-9]+)?)(ms|s|m)$".r
    raw match
      case pattern(amount, unit) =>
        val nanos =
          try
            val factor = unit match
              case "ms" => BigDecimal(1000000)
              case "s"  => BigDecimal(1000000000)
              case "m"  => BigDecimal(60000000000L)
            (BigDecimal(amount) * factor).toLongExact
          catch case _: ArithmeticException => -1L
        if nanos > 0 then Right(FiniteDuration(nanos, NANOSECONDS))
        else Left(s"--cut-time-limit must be a positive duration (received: $raw)")
      case _ => Left(s"invalid --cut-time-limit: $raw (expected a positive duration such as 1s or 250ms)")

  private def readGraphInput(input: String): Either[String, DepsGraph] =
    val text =
      if input == "-" then new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      else
        val path = os.Path(input, os.pwd)
        if !os.exists(path) then return Left(s"input path does not exist: $path")
        if !os.isFile(path) then return Left(s"not a file: $path")
        os.read(path)
    try Right(text.parseJson[DepsGraph])
    catch
      case e: ba.sake.tupson.TupsonException => Left(s"failed to parse json: ${e.getMessage}")

  private def readReportInput(input: String): Either[String, ba.sake.codeps.report.MetricsReport] =
    val text =
      if input == "-" then new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      else
        val path = os.Path(input, os.pwd)
        if !os.exists(path) then return Left(s"report path does not exist: $path")
        if !os.isFile(path) then return Left(s"report path is not a file: $path")
        os.read(path)
    ReportInspector.parse(text).left.map(err => s"failed to parse report json: $err")

  /** `--test-pattern` requires `--skip-tests`; when given it replaces the built-in patterns. */
  private def testPatternsOrError(skipTests: Boolean, testPattern: Seq[String]): Either[String, Option[Seq[String]]] =
    if testPattern.nonEmpty && !skipTests then Left("--test-pattern requires --skip-tests")
    else Right(if skipTests then Some(if testPattern.nonEmpty then testPattern else TestFilter.defaultPatterns) else None)

  private def parseRules(collapse: Seq[String]): Either[String, Seq[CollapseRule]] =
    collapse.foldLeft(Right(Nil): Either[String, Seq[CollapseRule]]) { (acc, pattern) =>
      for
        rules <- acc
        rule  <- CollapseRule.parse(pattern)
      yield rules :+ rule
    }

  private def writeOutput(content: String, out: Option[String]): Unit =
    out match
      case Some(path) => os.write.over(os.Path(path, os.pwd), content)
      case None       => print(content)

  private def shouldColor(format: ReportFormat, mode: ColorMode, out: Option[String]): Boolean =
    format match
      case ReportFormat.Json => false
      case ReportFormat.Table =>
        mode match
          case ColorMode.Never  => false
          case ColorMode.Always => true
          case ColorMode.Auto   =>
            out match
              case Some(path) if isTextOutput(path) => false
              case Some(_)                          => false
              case None                             => System.console() != null
      case ReportFormat.Markdown => false

  private def isTextOutput(path: String): Boolean =
    val lower = path.toLowerCase(Locale.ROOT)
    lower.endsWith(".txt") || lower.endsWith(".md")
