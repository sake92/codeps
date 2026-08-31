package ba.sake.codeps.cli

import ba.sake.codeps.graph.{Aggregator, Collapser, TestFilter}
import ba.sake.codeps.model.{CollapseRule, DepsGraph}
import ba.sake.codeps.report.{MetricsCalculator, ReportTable}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.semanticdb.SemanticDbParser
import ba.sake.tupson.{*, given}
import mainargs.{arg, main, ParserForMethods, TokensReader}

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
    case Json, Table

  given TokensReader.Simple[ReportFormat] with
    def shortName: String = "format"
    def read(strs: Seq[String]): Either[String, ReportFormat] =
      strs match
        case Seq("json")  => Right(ReportFormat.Json)
        case Seq("table") => Right(ReportFormat.Table)
        case Seq(other)   => Left(s"unknown format: $other (expected json or table)")
        case _            => Left("expected exactly one format")

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
                  val workspaceRoot = root.map(r => os.Path(r, os.pwd)).getOrElse(os.pwd)
                  val files = paths.flatMap(d => os.walk(d).filter(_.ext == "semanticdb").toSeq)
                  if files.isEmpty then
                    System.err.println("error: no .semanticdb files found")
                    1
                  else
                    for f <- files do
                      SemanticDbParser.parse(os.read.bytes(f), workspaceRoot.toNIO) match
                        case Right(d)  => deps = deps.merge(d)
                        case Left(err) => System.err.println(s"warning: $err")
                    writeOutput(Aggregator.fileLevel(deps.withoutDanglingEdges).toJson(spaces = 2, sort = true), out)
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
      out: Option[String],
      input: String
  )

  @main
  def reportPackages(
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table,
      @arg(name = "include") include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(name = "skip-tests") skipTests: mainargs.Flag,
      @arg(name = "test-pattern") testPattern: Seq[String] = Nil,
      @arg(name = "all") all: mainargs.Flag,
      @arg(short = 'o') out: Option[String],
      @arg(short = 'i', name = "input") input: String
  ): Int = runReport(MetricsCalculator.Scope.Packages, ReportOptions(format, include, exclude, collapse,
    skipTests.value, testPattern, all.value, out, input))

  @main
  def reportFiles(
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Table,
      @arg(name = "include") include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(name = "skip-tests") skipTests: mainargs.Flag,
      @arg(name = "test-pattern") testPattern: Seq[String] = Nil,
      @arg(name = "all") all: mainargs.Flag,
      @arg(short = 'o') out: Option[String],
      @arg(short = 'i', name = "input") input: String
  ): Int = runReport(MetricsCalculator.Scope.Files, ReportOptions(format, include, exclude, collapse,
    skipTests.value, testPattern, all.value, out, input))

  private def runReport(scope: MetricsCalculator.Scope, options: ReportOptions): Int =
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
              MetricsCalculator.run(graph, scope, options.include, options.exclude, rules, patterns).map { metricsReport =>
                options.format match
                  case ReportFormat.Json  => metricsReport.toJson(spaces = 2, sort = true)
                  case ReportFormat.Table => ReportTable.render(metricsReport, showAll = options.showAll)
              }
            } match
              case Left(err) =>
                System.err.println(s"error: $err")
                1
              case Right(content) =>
                writeOutput(content, options.out)
                0

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
