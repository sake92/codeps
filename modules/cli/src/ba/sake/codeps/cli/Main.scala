package ba.sake.codeps.cli

import ba.sake.codeps.exporting.{DotExporter, MermaidExporter, OutputFormat}
import ba.sake.codeps.graph.{Aggregator, Collapser, CycleDetector, Filter, GraphBuilder}
import ba.sake.codeps.model.{CollapseRule, DepsGraph}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.semanticdb.SemanticDbParser
import ba.sake.tupson.{*, given}
import mainargs.{arg, main, Leftover, ParserForMethods, TokensReader}

object Main:

  def main(args: Array[String]): Unit = sys.exit(run(args))

  /** Testable entry point: returns the process exit code. */
  def run(args: Array[String]): Int =
    ParserForMethods(this).runEither(reorder(args)) match
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
    val valueFlags = Set("-i", "--include", "-e", "--exclude", "-c", "--collapse", "-f", "--format",
      "-g", "--granularity", "-o", "--out", "--from", "--root")
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

  given TokensReader.Simple[Aggregator.Level] with
    def shortName: String = "granularity"
    def read(strs: Seq[String]): Either[String, Aggregator.Level] =
      strs match
        case Seq("package") => Right(Aggregator.Level.Package)
        case Seq("file")    => Right(Aggregator.Level.File)
        case Seq("type")    => Right(Aggregator.Level.Type)
        case Seq("member")  => Right(Aggregator.Level.Member)
        case Seq(other)     => Left(s"unknown granularity: $other (expected package, file, type or member)")
        case _              => Left("expected exactly one granularity")

  given TokensReader.Simple[OutputFormat] with
    def shortName: String = "format"
    def read(strs: Seq[String]): Either[String, OutputFormat] =
      strs match
        case Seq("dot")     => Right(OutputFormat.Dot)
        case Seq("mermaid") => Right(OutputFormat.Mermaid)
        case Seq(other)     => Left(s"unknown format: $other (expected dot or mermaid)")
        case _              => Left("expected exactly one format")

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
  def analyze(
      @arg(short = 'g') granularity: Aggregator.Level,
      @arg(short = 'f') format: OutputFormat,
      @arg(short = 'i') include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(short = 'o') out: Option[String],
      leftover: Leftover[String]
  ): Int =
    val inputs = leftover.value
    if inputs.isEmpty then
      System.err.println("error: exactly one input is required (a json file, or '-' for stdin)")
      1
    else if inputs.size > 1 then
      System.err.println("error: expected exactly one input (a json file, or '-' for stdin)")
      1
    else
      val input = inputs.head
      val text =
        if input == "-" then
          new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        else
          val path = os.Path(input, os.pwd)
          if !os.exists(path) then
            System.err.println(s"error: input path does not exist: $path")
            return 1
          os.read(path)
      try
        val graph = text.parseJson[DepsGraph]
        val filtered = Filter(graph, include, exclude)
        if filtered.nodes.isEmpty then
          System.err.println("error: no nodes remain after filtering")
          1
        else
          val rulesResult = collapse.foldLeft(Right(Nil): Either[String, Seq[CollapseRule]]) { (acc, pattern) =>
            for
              rules <- acc
              rule  <- CollapseRule.parse(pattern)
            yield rules :+ rule
          }
          rulesResult match
            case Left(err) =>
              System.err.println(s"error: $err")
              1
            case Right(rules) =>
              val (nodes, edges) = Aggregator.aggregate(filtered, granularity)
              val (collapsedNodes, collapsedEdges) = Collapser.collapse(nodes, edges, rules)
              val g = GraphBuilder.build(collapsedNodes, collapsedEdges)
              val cycles = CycleDetector.detect(g)
              val content = format match
                case OutputFormat.Dot     => DotExporter.render(g, cycles)
                case OutputFormat.Mermaid => MermaidExporter.render(g, cycles)
              writeOutput(content, out)
              0
      catch
        case e: ba.sake.tupson.TupsonException =>
          System.err.println(s"error: failed to parse json: ${e.getMessage}")
          1

  private def writeOutput(content: String, out: Option[String]): Unit =
    out match
      case Some(path) => os.write.over(os.Path(path, os.pwd), content)
      case None       => print(content)
