package ba.sake.codeps.cli

import ba.sake.codeps.graph.{Collapser, TestFilter}
import ba.sake.codeps.model.{CollapseRule, DepsGraph}
import ba.sake.codeps.report.{MetricsCalculator, ReportTable}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.semanticdb.SemanticDbParser
import ba.sake.tupson.{*, given}
import mainargs.{arg, main, Leftover, ParserForMethods, TokensReader}

object Main:

  def main(args: Array[String]): Unit = sys.exit(run(args))

  /** Testable entry point: returns the process exit code. */
  def run(args: Array[String]): Int =
    val parser = ParserForMethods(this)
    // mainargs 0.7.8 strips the subcommand name only when there are 2+ @main methods;
    // with a single main it expects the args without it. We keep the subcommand-first
    // CLI contract either way, stripping the leading command token here when mainargs won't.
    val effective =
      if parser.mains.value.size == 1 then
        args.toSeq match
          case Seq() => Seq()
          case head +: tail =>
            if parser.mains.value.exists(_.name(mainargs.Util.nullNameMapper).contains(head)) then reorderRest(tail)
            else reorderRest(args.toSeq)
      else reorder(args)
    parser.runEither(effective) match
      case Left(err) =>
        System.err.println(err)
        1
      case Right(code: Int) => code
      case Right(_)         => 0 // unreachable: subcommands return Int

  /** mainargs 0.7.x requires named args to precede Leftover positionals; move bare tokens to the end. */
  private def reorder(args: Seq[String]): Seq[String] =
    if args.isEmpty then args
    else if args(0).startsWith("-") then args
    else args(0) +: reorderRest(args.drop(1))

  private def reorderRest(rest: Seq[String]): Seq[String] =
    val valueFlags = Set("-i", "--include", "-e", "--exclude", "-c", "--collapse",
      "-f", "--format", "-s", "--scope", "-o", "--out", "--from", "--root", "--test-pattern")
    val named    = Seq.newBuilder[String]
    val leftover = Seq.newBuilder[String]
    var i = 0
    while i < rest.length do
      val a = rest(i)
      if a == "-" then leftover += a // stdin marker, not a flag
      else if a.startsWith("-") then
        named += a
        if valueFlags.contains(a) && i + 1 < rest.length then
          named += rest(i + 1)
          i += 1
      else leftover += a
      i += 1
    named.result() ++ leftover.result()

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

  enum ReportScope:
    case Packages, Files

  given TokensReader.Simple[ReportScope] with
    def shortName: String = "scope"
    def read(strs: Seq[String]): Either[String, ReportScope] =
      strs match
        case Seq("packages") => Right(ReportScope.Packages)
        case Seq("files")    => Right(ReportScope.Files)
        case Seq(other)      => Left(s"unknown scope: $other (expected packages or files)")
        case _               => Left("expected exactly one scope")

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
      leftover: Leftover[String]
  ): Int =
    val inputs = leftover.value
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
                    writeOutput(deps.withoutDanglingEdges.toJson(spaces = 2, sort = true), out)
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
                  writeOutput(deps.toJson(spaces = 2, sort = true), out)
                  0

  @main
  def report(
      @arg(short = 's', name = "scope") scope: ReportScope,
      @arg(short = 'f', name = "format") format: ReportFormat = ReportFormat.Json,
      @arg(short = 'i') include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(name = "skip-tests") skipTests: mainargs.Flag,
      @arg(name = "test-pattern") testPattern: Seq[String] = Nil,
      @arg(short = 'o') out: Option[String],
      leftover: Leftover[String]
  ): Int =
    testPatternsOrError(skipTests.value, testPattern) match
      case Left(err) =>
        System.err.println(s"error: $err")
        1
      case Right(patterns) =>
        val metricsScope = scope match
          case ReportScope.Packages => MetricsCalculator.Scope.Packages
          case ReportScope.Files    => MetricsCalculator.Scope.Files
        runOnGraph(leftover.value, out) { graph =>
          parseRules(collapse).flatMap { rules =>
            MetricsCalculator.run(graph, metricsScope, include, exclude, rules, patterns) match
              case Left(err) => Left(err)
              case Right(metricsReport) =>
                val content = format match
                  case ReportFormat.Json  => metricsReport.toJson(spaces = 2, sort = true)
                  case ReportFormat.Table => ReportTable.render(metricsReport)
                Right(content)
          }
        }

  /** Reads one json input (file or stdin) and runs `f` on it; errors go to stderr, exit 1. */
  private def runOnGraph(inputs: Seq[String], out: Option[String])(f: DepsGraph => Either[String, String]): Int =
    if inputs.isEmpty then
      System.err.println("error: exactly one input is required (a json file, or '-' for stdin)")
      1
    else if inputs.size > 1 then
      System.err.println("error: expected exactly one input (a json file, or '-' for stdin)")
      1
    else
      readGraphInput(inputs.head) match
        case Left(err) =>
          System.err.println(s"error: $err")
          1
        case Right(graph) =>
          f(graph) match
            case Left(err) =>
              System.err.println(s"error: $err")
              1
            case Right(content) =>
              writeOutput(content, out)
              0

  private def readGraphInput(input: String): Either[String, DepsGraph] =
    val text =
      if input == "-" then new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      else
        val path = os.Path(input, os.pwd)
        if !os.exists(path) then return Left(s"input path does not exist: $path")
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
