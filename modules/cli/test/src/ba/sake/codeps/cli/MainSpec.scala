package ba.sake.codeps.cli

import ba.sake.codeps.testing.FixtureCompiler

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

  test("nonexistent input exits 1") {
    val res = runCli("export", "--from", "semanticdb", "/nonexistent/path")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("input path does not exist"))
  }

  test("report --scope packages emits the flat metrics json") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"packages\""))
    assert(content.contains("\"generated_at\""))
    assert(content.contains("\"summary\""))
    assert(content.contains("\"nodes_in_cycles\""))
    assert(content.contains("\"knots\""))
    assert(content.contains("\"surface\""))
    assert(content.contains("\"articulation_points\""))
    assert(content.contains("\"com.example.modules.module2\"")) // a package node id in the fixture
  }

  test("report --scope files -i selects the package's files") {
    val out = os.pwd / "tmp" / "cli-test" / "report-files.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "files", "-i", "com.example.util", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"files\""))
    assert(content.contains("src/com/example/util/Helper.scala"))
    assert(!content.contains("src/com/example/app/Main.scala"))
  }

  test("report --scope files on jdeps data exits 1") {
    val res = runCli("report", "--scope", "files", exportJdepsJson("deps-jdeps-v2.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no file nodes found in the input"))
  }

  test("report --format table renders the same data as text") {
    val out = os.pwd / "tmp" / "cli-test" / "report-table.txt"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "--format", "table", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.startsWith("scope: packages"))
    assert(content.contains("Summary"))
    assert(content.contains("Knots"))
    assert(content.contains("Surface"))
  }

  test("report defaults --format to json") {
    val res = runCli("report", "--scope", "packages", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("\"summary\""))
  }

  test("report --skip-tests excludes test nodes") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-skip-tests.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "--skip-tests", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(!content.contains("HelperSpec"))
    assert(!content.contains("OnlyTestsHereSpec"))
  }

  test("report -c collapse merges packages") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-collapse.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "-c", "com.example.modules.**", exportJson("deps.json").toString, "-o", out.toString)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("com.example.modules"))
  }

  test("report: --test-pattern without --skip-tests exits 1") {
    val res = runCli("report", "--scope", "packages", "--test-pattern", "**/test/**", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("--test-pattern requires --skip-tests"))
  }

  test("bad scope exits non-zero") {
    val res = runCli("report", "--scope", "bogus", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown scope: bogus"))
  }

  test("bad report format exits non-zero") {
    val res = runCli("report", "--scope", "packages", "--format", "bogus", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown format: bogus"))
  }

  test("malformed json input exits 1") {
    val input = os.pwd / "tmp" / "cli-test" / "bad.json"
    os.makeDir.all(input / os.up)
    os.write.over(input, """{"nodes": "not-an-array"}""")
    val res = runCli("report", "--scope", "packages", input.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("failed to parse json"))
  }

  test("report reads from stdin with '-'") {
    val json = os.read(exportJson("deps.json"))
    val out = os.pwd / "tmp" / "cli-test" / "report-stdin.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        Seq[os.Shellable]("report", "--scope", "packages", "-", "-o", out.toString)
    val res = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, stdin = json)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"summary\""))
  }
