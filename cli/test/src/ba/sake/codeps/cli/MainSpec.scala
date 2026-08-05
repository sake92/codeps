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
  }

  test("empty result exits 1") {
    val res = runCli("semdb", semdbDir.toString, "--include", "no.such.pkg", "-f", "dot")
    assertEquals(res.exitCode, 1)
    assert(res.err.text().contains("no packages remain after filtering"))
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
