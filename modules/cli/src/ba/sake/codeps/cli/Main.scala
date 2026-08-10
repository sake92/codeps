package ba.sake.codeps.cli

import ba.sake.codeps.exporting.{DotExporter, JsonExporter, MermaidExporter, OutputFormat, RawJsonExporter}
import ba.sake.codeps.graph.{Collapser, Filter, GraphBuilder}
import ba.sake.codeps.model.{CollapseRule, PackageDeps}
import ba.sake.codeps.jdeps.JdepsParser
import ba.sake.codeps.json.JsonParser
import ba.sake.codeps.semanticdb.SemanticDbParser
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
    val valueFlags = Set("-i", "--include", "-e", "--exclude", "-c", "--collapse", "-f", "--format", "-o", "--out")
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

  given TokensReader.Simple[OutputFormat] with
    def shortName: String = "format"
    def read(strs: Seq[String]): Either[String, OutputFormat] =
      strs match
        case Seq("dot")     => Right(OutputFormat.Dot)
        case Seq("json")    => Right(OutputFormat.Json)
        case Seq("mermaid") => Right(OutputFormat.Mermaid)
        case Seq("raw")     => Right(OutputFormat.Raw)
        case Seq(other)     => Left(s"unknown format: $other (expected dot, json, mermaid or raw)")
        case _              => Left("expected exactly one format")

  @main
  def semdb(
      @arg(short = 'i') include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(short = 'f') format: OutputFormat,
      @arg(short = 'o') out: Option[String],
      leftover: Leftover[String]
  ): Int =
    val dirs = leftover.value
    if dirs.isEmpty then
      System.err.println("error: at least one directory is required")
      1
    else
      dirs.map(d => os.Path(d, os.pwd)).find(!os.exists(_)) match
        case Some(missing) =>
          System.err.println(s"error: input path does not exist: $missing")
          1
        case None =>
          val files = dirs.flatMap(d => os.walk(os.Path(d, os.pwd)).filter(_.ext == "semanticdb").toSeq)
          analyze(files, SemanticDbParser.parse, include, exclude, collapse, format, out)

  @main
  def jdeps(
      @arg(short = 'i') include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(short = 'f') format: OutputFormat,
      @arg(short = 'o') out: Option[String],
      leftover: Leftover[String]
  ): Int =
    val files = leftover.value
    if files.isEmpty then
      System.err.println("error: at least one file is required")
      1
    else
      val paths = files.map(f => os.Path(f, os.pwd))
      analyze(paths, bytes => Right(JdepsParser.parse(new String(bytes))), include, exclude, collapse, format, out)

  @main
  def json(
      @arg(short = 'i') include: Seq[String],
      @arg(short = 'e') exclude: Seq[String],
      @arg(short = 'c') collapse: Seq[String],
      @arg(short = 'f') format: OutputFormat,
      @arg(short = 'o') out: Option[String],
      leftover: Leftover[String]
  ): Int =
    val inputs = leftover.value
    if inputs.isEmpty then
      System.err.println("error: at least one input is required (a json file, or '-' for stdin)")
      1
    else if inputs.size > 1 then
      System.err.println("error: expected exactly one input (a json file, or '-' for stdin)")
      1
    else
      val input = inputs.head
      if input == "-" then
        parseJsonInput(new String(System.in.readAllBytes()), include, exclude, collapse, format, out)
      else
        val path = os.Path(input, os.pwd)
        if !os.exists(path) then
          System.err.println(s"error: input path does not exist: $path")
          1
        else
          parseJsonInput(os.read(path), include, exclude, collapse, format, out)

  private def parseJsonInput(
      text: String,
      include: Seq[String],
      exclude: Seq[String],
      collapse: Seq[String],
      format: OutputFormat,
      out: Option[String]
  ): Int =
    JsonParser.parse(text) match
      case Left(err) =>
        System.err.println(s"error: $err")
        1
      case Right(deps) =>
        process(deps, include, exclude, collapse, format, out)

  private def analyze(
      files: Seq[os.Path],
      parse: Array[Byte] => Either[String, PackageDeps],
      include: Seq[String],
      exclude: Seq[String],
      collapse: Seq[String],
      format: OutputFormat,
      out: Option[String]
  ): Int =
    if files.isEmpty then
      System.err.println("error: no files found")
      1
    else
      files.find(f => !os.exists(f)) match
        case Some(f) =>
          System.err.println(s"error: input path does not exist: $f")
          1
        case None =>
          var deps = PackageDeps.empty
          for f <- files do
            parse(os.read.bytes(f)) match
              case Right(d)  => deps = deps.merge(d)
              case Left(err) => System.err.println(s"warning: $err")
          process(deps, include, exclude, collapse, format, out)

  private def process(
      deps: PackageDeps,
      include: Seq[String],
      exclude: Seq[String],
      collapse: Seq[String],
      format: OutputFormat,
      out: Option[String]
  ): Int =
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
        val (universe, filteredEdges, filteredCounts) = Filter(deps.own, deps.edges, deps.stats, include, exclude)
        if universe.isEmpty then
          System.err.println("error: no packages remain after filtering")
          1
        else if format == OutputFormat.Raw then
          // raw = the common JSON input format, emitted after filtering but before collapsing
          // (collapsing would destroy the edges needed for further passes)
          writeOutput(RawJsonExporter.render(PackageDeps(universe, filteredEdges, filteredCounts)), out)
          0
        else
          val (collapsedNodes, collapsedEdges, collapsedCounts) =
            Collapser.collapse(universe, filteredEdges, filteredCounts, rules)
          val graph = GraphBuilder.build(collapsedNodes, collapsedEdges)
          val content = format match
            case OutputFormat.Dot     => DotExporter.render(graph)
            case OutputFormat.Json    => JsonExporter.render(graph, collapsedCounts)
            case OutputFormat.Mermaid => MermaidExporter.render(graph)
            case OutputFormat.Raw     => "" // handled above
          writeOutput(content, out)
          0

  private def writeOutput(content: String, out: Option[String]): Unit =
    out match
      case Some(path) => os.write.over(os.Path(path, os.pwd), content)
      case None       => print(content)
