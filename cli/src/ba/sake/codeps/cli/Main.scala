package ba.sake.codeps.cli

import ba.sake.codeps.exporting.{DotExporter, JsonExporter, MermaidExporter, OutputFormat}
import ba.sake.codeps.graph.{Collapser, Filter, GraphBuilder}
import ba.sake.codeps.model.{CollapseRule, PackageEdge}
import ba.sake.codeps.jdeps.JdepsParser
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
      case Right(_)         => 0 // unreachable: both subcommands return Int

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
      if a.startsWith("-") then
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
        case Seq(other)     => Left(s"unknown format: $other (expected dot, json or mermaid)")
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

  private def analyze(
      files: Seq[os.Path],
      parse: Array[Byte] => Either[String, (Set[String], Set[PackageEdge])],
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
              var own   = Set.empty[String]
              var edges = Set.empty[PackageEdge]
              for f <- files do
                parse(os.read.bytes(f)) match
                  case Right((o, e)) => own ++= o; edges ++= e
                  case Left(err)     => System.err.println(s"warning: $err")
              val (universe, filteredEdges) = Filter(own, edges, include, exclude)
              if universe.isEmpty then
                System.err.println("error: no packages remain after filtering")
                1
              else
                val (collapsedNodes, collapsedEdges) = Collapser.collapse(universe, filteredEdges, rules)
                val graph = GraphBuilder.build(collapsedNodes, collapsedEdges)
                val content = format match
                  case OutputFormat.Dot     => DotExporter.render(graph)
                  case OutputFormat.Json    => JsonExporter.render(graph)
                  case OutputFormat.Mermaid => MermaidExporter.render(graph)
                out match
                  case Some(path) => os.write.over(os.Path(path, os.pwd), content)
                  case None       => print(content)
                0
