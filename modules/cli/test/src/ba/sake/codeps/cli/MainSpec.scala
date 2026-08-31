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

  private def runCliEnv(env: Map[String, String], args: String*): os.CommandResult =
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        args.map(s => s: os.Shellable)
    os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, env = env)

  private def exportJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "semanticdb", "-o", out.toString, "--input", semdbDir.toString)
    assertEquals(res.exitCode, 0)
    out

  private def exportJdepsJson(name: String): os.Path =
    val out = os.pwd / "tmp" / "cli-test" / name
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", "-o", out.toString, "--input", FixtureCompiler.jdepsFile.toString)
    assertEquals(res.exitCode, 0)
    out

  test("export --from semanticdb emits package and file nodes only") {
    val content = os.read(exportJson("deps.json"))
    assert(content.contains("\"kind\": \"package\""))
    assert(content.contains("\"kind\": \"file\""))
    assert(!content.contains("\"kind\": \"type\""))
    assert(!content.contains("\"kind\": \"member\""))
    assert(!content.contains("com.example.modules.module1.Service1")) // types are gone
    assert(content.contains("com.example.modules.module1")) // packages remain
    assert(content.contains("src/com/example/util/Helper.scala")) // files remain
    assert(content.contains("\"parentId\": \"com.example.util\"")) // file -> package link
  }

  test("export --from jdeps emits package-level json") {
    val out = os.pwd / "tmp" / "cli-test" / "deps-jdeps.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("export", "--from", "jdeps", "-o", out.toString, "--input", FixtureCompiler.jdepsFile.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module2\""))
    assert(content.contains("\"kind\": \"package\""))
    assert(!content.contains("\"kind\": \"type\""))
    assert(!content.contains("\"kind\": \"member\""))
    assert(!content.contains("\"kind\": \"file\""))
  }

  test("nonexistent input exits 1") {
    val res = runCli("export", "--from", "semanticdb", "--input", "/nonexistent/path")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("input path does not exist"))
  }

  test("report --scope packages emits the flat metrics json") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "--format", "json", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"packages\""))
    assert(content.contains("\"generatedAt\""))
    assert(""""generatedAt": "\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""".r.findFirstIn(content).nonEmpty) // second precision, UTC
    assert(content.contains("\"summary\""))
    assert(content.contains("\"nodesInCycles\""))
    assert(content.contains("\"cycles\""))
    assert(!content.contains("\"knots\""))
    assert(content.contains("\"surface\""))
    assert(!content.contains("articulation_points"))
    assert(!content.contains("\"effect\""))
    assert(!content.contains("\"new_size\""))
    assert(content.contains("\"com.example.modules.module2\""))
  }

  test("report --scope files --include selects the package's files") {
    val out = os.pwd / "tmp" / "cli-test" / "report-files.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "files", "--include", "com.example.util", "--format", "json", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"scope\": \"files\""))
    assert(content.contains("src/com/example/util/Helper.scala"))
    assert(!content.contains("src/com/example/app/Main.scala"))
  }

  test("report --scope files on jdeps data exits 1") {
    val res = runCli("report", "--scope", "files", "--input", exportJdepsJson("deps-jdeps-v2.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no file nodes found in the input"))
  }

  test("report --scope packages on jdeps data works") {
    val res = runCli("report", "--scope", "packages", "--format", "json",
      "--input", exportJdepsJson("deps-jdeps-report.json").toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("com.example.modules.module2"))
  }

  test("report --format table renders the same data as text") {
    val out = os.pwd / "tmp" / "cli-test" / "report-table.txt"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "--format", "table", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.startsWith("scope: packages"))
    assert(content.contains("Summary"))
    assert(content.contains("Cycles"))
    assert(!content.contains("Articulation points"))
    assert(content.contains("Surface"))
  }

  test("report defaults --format to table") {
    val res = runCli("report", "--scope", "packages", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().startsWith("scope: packages"))
    assert(res.out.text().contains("Summary"))
    assert(res.out.text().contains("Cycles"))
  }

  test("report on the checked-in cyclic fixture finds the module cycle (homepage example)") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val outJson = os.pwd / "tmp" / "cli-test" / "report-cyclic-fixture.json"
    os.makeDir.all(outJson / os.up)
    os.remove.all(outJson)
    val jsonRes = runCli("report", "--scope", "packages", "--format", "json", "-o", outJson.toString, "--input", cyclic.toString)
    assertEquals(jsonRes.exitCode, 0)
    val content = os.read(outJson)
    assert(content.contains("\"scope\": \"packages\""))
    assert(content.contains("\"id\": \"scc:com.example.modules.module1\""))
    assert(content.contains("\"solutions\""))
    assert(content.contains("\"mutPorts\": 0"))
    assert(content.contains("\"nodesInCycles\": 2"))
    // table format renders the same cycle with camelCase headers
    val tableRes = runCli("report", "--scope", "packages", "--input", cyclic.toString)
    assertEquals(tableRes.exitCode, 0)
    assert(tableRes.out.text().contains("scc:com.example.modules.module1"))
    assert(tableRes.out.text().contains("Cycle scc:com.example.modules.module1"))
    assert(tableRes.out.text().contains("solution 1:"))
    assert(tableRes.out.text().contains("minCutsEstimate"))
    assert(tableRes.out.text().contains("mutPorts"))
    assert(!tableRes.out.text().contains("mut_ports"))
  }

  test("report --skip-tests excludes test nodes") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-skip-tests.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "--skip-tests", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(!content.contains("HelperSpec"))
    assert(!content.contains("OnlyTestsHereSpec"))
  }

  test("report -c collapse merges packages") {
    val out = os.pwd / "tmp" / "cli-test" / "report-v2-collapse.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("report", "--scope", "packages", "-c", "com.example.modules.**", "-o", out.toString, "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("com.example.modules"))
  }

  test("report: --test-pattern without --skip-tests exits 1") {
    val res = runCli("report", "--scope", "packages", "--test-pattern", "**/test/**", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("--test-pattern requires --skip-tests"))
  }

  test("bad scope exits non-zero") {
    val res = runCli("report", "--scope", "bogus", "--input", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown scope: bogus"))
  }

  test("bad report format exits non-zero") {
    val res = runCli("report", "--scope", "packages", "--format", "bogus", "--input", exportJson("deps.json").toString)
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown format: bogus"))
  }

  test("malformed json input exits 1") {
    val input = os.pwd / "tmp" / "cli-test" / "bad.json"
    os.makeDir.all(input / os.up)
    os.write.over(input, """{"nodes": "not-an-array"}""")
    val res = runCli("report", "--scope", "packages", "--input", input.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("failed to parse json"))
  }

  test("report on a directory input exits 1 with a clean error") {
    val res = runCli("report", "--scope", "packages", "--input", "testFixtures")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("not a file"))
    assert(!res.err.text().contains("Is a directory")) // no raw stack trace
  }

  test("report reads from stdin with --input -") {
    val json = os.read(exportJson("deps.json"))
    val out = os.pwd / "tmp" / "cli-test" / "report-stdin.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        Seq[os.Shellable]("report", "--scope", "packages", "--format", "json", "-o", out.toString, "--input", "-")
    val res = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, stdin = json)
    assertEquals(res.exitCode, 0)
    assert(os.read(out).contains("\"summary\""))
  }

  test("unknown flag exits 1 with a clean error") {
    val res = runCli("report", "--scope", "packages", "--bogus", "--input", exportJson("deps.json").toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("\"--bogus\"")) // mainargs' own unknown-argument error
    assert(!res.err.text().contains("Exception"))  // no raw stack trace
  }

  test("positional token is rejected by the strict parser") {
    val res = runCli("report", "--scope", "packages", "/tmp/opencode/nonexistent-pos.json")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("Unknown argument"))
    assert(!res.err.text().contains("Exception")) // no raw stack trace
  }

  test("--version prints the version") {
    val res = runCli("--version")
    assertEquals(res.exitCode, 0)
    assert(res.out.text().trim.matches("[0-9A-Za-z.\\-]+"))
  }

  test("report --help prints usage") {
    val res = runCli("report", "--help")
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("Available subcommands"))
  }

  test("SOURCE_DATE_EPOCH pins generatedAt") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCliEnv(Map("SOURCE_DATE_EPOCH" -> "1700000000"),
      "report", "--scope", "packages", "--format", "json", "--input", cyclic.toString)
    assertEquals(res.exitCode, 0)
    assert(res.out.text().contains("\"generatedAt\": \"2023-11-14T22:13:20Z\""))
  }

  test("invalid SOURCE_DATE_EPOCH exits 1") {
    val cyclic = os.pwd / "testFixtures" / "cyclic.json"
    val res = runCliEnv(Map("SOURCE_DATE_EPOCH" -> "abc"),
      "report", "--scope", "packages", "--format", "json", "--input", cyclic.toString)
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("invalid SOURCE_DATE_EPOCH"))
  }
