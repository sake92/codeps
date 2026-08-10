package ba.sake.codeps.cli

import ba.sake.codeps.testing.FixtureCompiler

class MainSpec extends munit.FunSuite:

  override def beforeAll(): Unit = FixtureCompiler.ensure()

  val semdbDir = FixtureCompiler.classesDir / "META-INF" / "semanticdb"

  /** Runs the real CLI as a subprocess (os.call equivalent in os-lib 0.11). */
  private def runCli(args: String*): os.CommandResult =
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        args.map(s => s: os.Shellable)
    os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe)

  test("semdb subcommand produces dot output file") {
    val out = os.pwd / "tmp" / "cli-test" / "out.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("semdb", semdbDir.toString, "--include", "com.example", "-f", "dot", "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.startsWith("digraph deps {"))
    assert(content.contains("\"com.example.modules.module1\" -> \"com.example.util\";"))
  }

  test("semdb subcommand collapses packages") {
    val out = os.pwd / "tmp" / "cli-test" / "out-collapsed.dot"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli(
      "semdb", semdbDir.toString,
      "--include", "com.example",
      "--collapse", "com.example.modules.**",
      "-f", "dot", "-o", out.toString
    )
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules\" -> \"com.example.util\";"))
  }

  test("jdeps subcommand works") {
    val out = os.pwd / "tmp" / "cli-test" / "out-jdeps.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("jdeps", FixtureCompiler.jdepsFile.toString, "--include", "com.example", "-f", "json", "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.modules.module1\""))
    // jdeps carries no per-package file/class info
    assert(!content.contains("nodeInfo"))
  }

  test("semdb json output includes nodeInfo with file/class counts") {
    val out = os.pwd / "tmp" / "cli-test" / "out-nodeinfo.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val res = runCli("semdb", semdbDir.toString, "--include", "com.example", "-f", "json", "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"nodeInfo\""))
    assert(content.contains("\"com.example.util\": {\"files\": 1, \"classes\": 1}"))
  }

  test("empty result exits 1") {
    val res = runCli("semdb", semdbDir.toString, "--include", "no.such.pkg", "-f", "dot")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no packages remain after filtering"))
  }

  test("json subcommand produces dot output") {
    val input = os.pwd / "tmp" / "cli-test" / "input.json"
    val out   = os.pwd / "tmp" / "cli-test" / "out-json.dot"
    os.makeDir.all(out / os.up)
    os.write.over(
      input,
      """{"own": ["com.example.a", "com.example.b"], "edges": [{"source": "com.example.a", "target": "com.example.b"}]}"""
    )
    os.remove.all(out)
    val res = runCli("json", input.toString, "--include", "com.example", "-f", "dot", "-o", out.toString)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.a\" -> \"com.example.b\";"))
  }

  test("json subcommand reads from stdin with '-'") {
    val out = os.pwd / "tmp" / "cli-test" / "out-stdin.json"
    os.makeDir.all(out / os.up)
    os.remove.all(out)
    val input = """{"own": ["com.example.a", "com.example.b"], "edges": [{"source": "com.example.a", "target": "com.example.b"}]}"""
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++
        Seq[os.Shellable]("json", "-", "--include", "com.example", "-f", "json", "-o", out.toString)
    val res = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe, stdin = input)
    assertEquals(res.exitCode, 0)
    val content = os.read(out)
    assert(content.contains("\"com.example.a\""))
    assert(content.contains("\"com.example.b\""))
  }

  test("json subcommand round-trips semdb raw output") {
    val rawFile  = os.pwd / "tmp" / "cli-test" / "deps-raw.json"
    val viaJson  = os.pwd / "tmp" / "cli-test" / "out-via-json.json"
    val direct   = os.pwd / "tmp" / "cli-test" / "out-direct.json"
    os.makeDir.all(rawFile / os.up)
    os.remove.all(rawFile)
    os.remove.all(viaJson)
    os.remove.all(direct)
    val rawRes = runCli("semdb", semdbDir.toString, "--include", "com.example", "-f", "raw", "-o", rawFile.toString)
    assertEquals(rawRes.exitCode, 0)
    val rawContent = os.read(rawFile)
    assert(rawContent.contains("\"own\""))
    assert(rawContent.contains("\"edges\""))
    assert(rawContent.contains("\"stats\""))
    val viaJsonRes = runCli("json", rawFile.toString, "--include", "com.example", "-f", "json", "-o", viaJson.toString)
    assertEquals(viaJsonRes.exitCode, 0)
    val directRes = runCli("semdb", semdbDir.toString, "--include", "com.example", "-f", "json", "-o", direct.toString)
    assertEquals(directRes.exitCode, 0)
    assertEquals(os.read(viaJson), os.read(direct))
  }

  test("malformed json input exits 1 with error") {
    val input = os.pwd / "tmp" / "cli-test" / "bad.json"
    os.makeDir.all(input / os.up)
    os.write.over(input, """{"own": "not-an-array"}""")
    val res = runCli("json", input.toString, "--include", "com.example", "-f", "dot")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("failed to parse json"))
  }

  test("nonexistent input exits 1 with clean error") {
    val res = runCli("semdb", "/nonexistent/path", "--include", "com.example", "-f", "dot")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("input path does not exist"))
  }

  test("bad format exits non-zero") {
    val res = runCli("semdb", semdbDir.toString, "--include", "com.example", "-f", "bogus")
    assert(res.exitCode != 0)
    assert(res.err.text().contains("unknown format: bogus"))
  }

  test("bad collapse rule exits 1") {
    val res = runCli("semdb", semdbDir.toString, "--include", "com.example", "--collapse", "a.b.c", "-f", "dot")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("collapse rule must end with '**' or '*'"))
  }
