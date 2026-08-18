package ba.sake.codeps.cli

import ba.sake.codeps.model.Edge
import ba.sake.codeps.report.AnalysisReport
import ba.sake.codeps.testing.FixtureCompiler
import ba.sake.tupson.{*, given}

class MainSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val semdbDir = FixtureCompiler.classesDir / "META-INF" / "semanticdb"

  private def runCli(args: String*): os.CommandResult =
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        args.map(s => s: os.Shellable)
    os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe)

  private def exportJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "semanticdb", semdbDir.toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    out

  private def exportJdepsJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", FixtureCompiler.jdepsFile.toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    out

  test("export --from semanticdb emits the common json format") {
    val content = os.read(exportJson("deps.json"))
    assert(content.contains("\"kind\": \"package\""))
    assert(content.contains("\"kind\": \"type\""))
    assert(content.contains("\"kind\": \"member\""))
    assert(content.contains("\"kind\": \"file\""))
    assert(content.contains("\"com.example.modules.module1.Service1\""))
  }

  test("export --from jdeps emits type-level json") {
    val out = os.pwd / "tmp" / "cli-test" / "deps-jdeps.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", FixtureCompiler.jdepsFile.toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module2.Service2\""))
    assert(!content.contains("\"kind\": \"member\""))
    assert(!content.contains("\"kind\": \"file\""))
  }

  test("draw -g package produces package-level dot") {
    val out = os.pwd / "tmp" / "cli-test" / "out.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("draw", "-g", "package", "-f", "dot", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.startsWith("digraph deps {"))
    assert(content.contains("\"com.example.modules.module1\" -> \"com.example.util\";"))
  }

  test("draw -g type shows type nodes, no package nodes") {
    val out = os.pwd / "tmp" / "cli-test" / "out-types.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("draw", "-g", "type", "-f", "dot", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module1.Service1\""))
    assert(!content.contains("\"com.example.modules.module1\"")) // packages dropped at type level
    assert(!content.contains("\"org.thirdparty\""))
  }

  test("draw accepts long-form flags with values after positionals") {
    val out = os.pwd / "tmp" / "cli-test" / "out-long.mmd"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    // reorder moves the positional json after all named args, so this must behave
    // like the short-form calls even though the positional follows named flags
    val res = runCli(
      "draw", "--granularity", "type", "--format", "mermaid", "--include", "com.example",
      exportJson("deps.json").toString, "-o", out.toString
    )
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"com.example.modules.module1.Service1\""))
  }

  test("draw -g file shows file nodes, no package nodes") {
    val out = os.pwd / "tmp" / "cli-test" / "out-files.mmd"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("draw", "-g", "file", "-f", "mermaid", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains(".scala"))
    assert(!content.contains("\"com.example.modules.module1\"")) // packages dropped at file level
    assert(!content.contains("\"org.thirdparty\""))
  }

  test("draw collapses packages") {
    val out = os.pwd / "tmp" / "cli-test" / "out-collapsed.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli(
      "draw", "-g", "package", "-f", "dot", "-c", "com.example.modules.**",
      exportJson("deps.json").toString, "-o", out.toString
    )
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"com.example.modules\" -> \"com.example.util\";"))
  }

  test("draw reads from stdin with '-'") {
    val json = os.read(exportJson("deps.json"))
    val out = os.pwd / "tmp" / "cli-test" / "out-stdin.mmd"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        Seq[os.Shellable]("draw", "-g", "package", "-f", "mermaid", "-", "-o", out.toString)
    val res = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, stdin = json)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"com.example.util\""))
  }

  test("empty result exits 1") {
    val res = runCli("draw", "-g", "package", "-f", "dot", "-i", "no.such.pkg", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no nodes remain after filtering"))
  }

  test("malformed json input exits 1") {
    val input = os.pwd / "tmp" / "cli-test" / "bad.json"
    os.makeDir.all(input / os.up)
    os.write.over(input, """{"nodes": "not-an-array"}""")
    val res = runCli("draw", "-g", "package", "-f", "dot", input.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("failed to parse json"))
  }

  test("bad granularity exits non-zero") {
    val res = runCli("draw", "-g", "bogus", "-f", "dot", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown granularity: bogus"))
  }

  test("bad format exits non-zero") {
    val res = runCli("draw", "-g", "package", "-f", "bogus", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown format: bogus"))
  }

  test("nonexistent input exits 1") {
    val res = runCli("export", "--from", "semanticdb", "/nonexistent/path")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("input path does not exist"))
  }

  test("bad collapse rule exits 1") {
    val res = runCli("draw", "-g", "package", "-f", "dot", "-c", "a.b.c", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("collapse rule must end with '**' or '*'"))
  }

  test("report emits multi-level analysis json") {
    val out = os.pwd / "tmp" / "cli-test" / "report.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"levels\""))
    assert(content.contains("\"package\""))
    assert(content.contains("\"file\""))
    assert(content.contains("\"type\""))
    assert(content.contains("\"member\""))
    assert(content.contains("\"suggestions\""))
    assert(content.contains("\"hardestKnots\""))
    assert(content.contains("\"breakEdges\""))
  }

  test("report edges carry weights summed from member-level references") {
    val out = os.pwd / "tmp" / "cli-test" / "report-weights.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val report = os.read(out).parseJson[AnalysisReport]
    val pkgEdges = report.levels("package").graph.get.edges
    assertEquals(pkgEdges, Set(
      Edge("com.example.app", "com.example.modules.module2", 2),
      Edge("com.example.modules.module1", "com.example.util", 2),
      Edge("com.example.modules.module2", "com.example.modules.module1", 2),
      Edge("com.example.specs", "com.example.util", 2),
      Edge("com.example.modules.module2", "org.thirdparty", 2)
    ))
    val fileEdges = report.levels("file").graph.get.edges
    assertEquals(fileEdges, Set(
      Edge("src/com/example/app/Main.scala", "src/com/example/modules/module2/Service2.scala", 3),
      Edge("src/com/example/modules/module1/Service1.scala", "src/com/example/util/Helper.scala", 3),
      Edge("src/com/example/modules/module2/Service2.scala", "src/com/example/modules/module1/Service1.scala", 3),
      Edge("src/com/example/specs/OnlyTestsHereSpec.scala", "src/com/example/util/Helper.scala", 3),
      Edge("src/com/example/util/HelperSpec.scala", "src/com/example/util/Helper.scala", 2),
      Edge("src/com/example/modules/module2/Service2.scala", "src/org/thirdparty/Ext.scala", 3)
    ))
  }

  test("report grades package cycles as bad and supports collapse") {
    val input = os.pwd / "tmp" / "cli-test" / "cyclic.json"
    os.makeDir.all(input / os.up)
    os.write.over(
      input,
      """{"nodes":[{"id":"p1","kind":"package"},{"id":"p2","kind":"package"},
        |{"id":"A.scala","kind":"file"},{"id":"B.scala","kind":"file"},
        |{"id":"p1.A","kind":"type","parentId":"p1","file":"A.scala"},
        |{"id":"p2.B","kind":"type","parentId":"p2","file":"B.scala"},
        |{"id":"p1.A#m","kind":"member","parentId":"p1.A","file":"A.scala"},
        |{"id":"p2.B#n","kind":"member","parentId":"p2.B","file":"B.scala"}],
        |"edges":[{"source":"p1.A#m","target":"p2.B#n"},{"source":"p2.B#n","target":"p1.A#m"}]}""".stripMargin
    )
    val out = os.pwd / "tmp" / "cli-test" / "report-cyclic.json"
    os.remove.all(out)
    val res = runCli("report", "-c", "p1.**", input.toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"severity\": \"bad\""))
    assert(content.contains("\"severity\": \"meh\""))
  }

  test("--test-pattern without --skip-tests exits 1") {
    val res = runCli("draw", "-g", "package", "-f", "dot", "--test-pattern", "**/test/**", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("--test-pattern requires --skip-tests"))
  }

  test("report: --test-pattern without --skip-tests exits 1") {
    val res = runCli("report", "--test-pattern", "**/test/**", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("--test-pattern requires --skip-tests"))
  }

  test("draw -g file --skip-tests drops test files, keeps main files") {
    val out = os.pwd / "tmp" / "cli-test" / "out-skip-tests.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("draw", "-g", "file", "-f", "dot", "--skip-tests", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("Helper.scala"))
    assert(!content.contains("HelperSpec.scala"))
    assert(!content.contains("OnlyTestsHereSpec.scala"))
  }

  test("draw -g package --skip-tests prunes test-only packages") {
    val out = os.pwd / "tmp" / "cli-test" / "out-skip-tests-pkg.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("draw", "-g", "package", "-f", "dot", "--skip-tests", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(!content.contains("com.example.specs"))
    assert(content.contains("com.example.util"))
  }

  test("report --skip-tests excludes test nodes from the report") {
    val out = os.pwd / "tmp" / "cli-test" / "report-skip-tests.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--skip-tests", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(!content.contains("HelperSpec"))
    assert(!content.contains("OnlyTestsHereSpec"))
    assert(content.contains("Helper.scala"))
  }

  test("--test-pattern replaces the built-in defaults") {
    val out = os.pwd / "tmp" / "cli-test" / "out-custom-pattern.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli(
      "draw", "-g", "file", "-f", "dot", "--skip-tests", "--test-pattern", "**/nonexistent/**",
      exportJson("deps.json").toString, "-o", out.toString
    )
    assertEquals(res.exitCode, 0)
    // defaults replaced by a pattern that matches nothing: the spec files survive
    assert(os.read(out).contains("HelperSpec.scala"))
  }

  test("jdeps data: --skip-tests is a no-op") {
    val out1 = os.pwd / "tmp" / "cli-test" / "jdeps-without.dot"
    val out2 = os.pwd / "tmp" / "cli-test" / "jdeps-with.dot"
    os.makeDir.all(out1 / os.up)
    os.remove.all(out1)
    os.remove.all(out2)
    val json = exportJdepsJson("deps-jdeps2.json").toString
    val res1 = runCli("draw", "-g", "type", "-f", "dot", json, "-o", out1.toString)
    val res2 = runCli("draw", "-g", "type", "-f", "dot", "--skip-tests", json, "-o", out2.toString)
    assertEquals(res1.exitCode, 0)
    assertEquals(res2.exitCode, 0)
    assertEquals(os.read(out1), os.read(out2))
  }
