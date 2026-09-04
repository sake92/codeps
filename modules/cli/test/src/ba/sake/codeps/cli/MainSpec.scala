package ba.sake.codeps.cli

class MainSpec extends munit.FunSuite:

  private def runCli(args: String*): os.CommandResult =
    val command: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++ args.map(value => value: os.Shellable)
    os.proc(command).call(cwd = os.pwd, check = false, stderr = os.Pipe)

  private val config = os.pwd / "tmp" / "cli-test" / "status-config.yaml"
  private val history = os.pwd / ".codeps" / "fixture.ndjson"
  private val output = os.pwd / ".codeps" / "out" / "fixture"

  override def beforeEach(context: BeforeEach): Unit =
    os.makeDir.all(config / os.up)
    os.write.over(config,
      """projects:
        |  fixture:
        |    source: export
        |    inputs: [testFixtures/cyclic.json]
        |    scope: packages
        |    max-snapshot-age: off
        |""".stripMargin)
    os.remove.all(history)
    os.remove.all(output)

  override def afterEach(context: AfterEach): Unit =
    os.remove.all(history)
    os.remove.all(output)

  test("status records a configured project and renders its dashboard") {
    val result = runCli("status", "--config", config.toString, "--commit", "abc123", "--generated-at", "2026-09-04T10:00:00Z")
    assertEquals(result.exitCode, 0)
    assert(os.exists(history))
    assert(os.exists(output / "report.json"))
    assert(os.exists(output / "index.html"))
    val html = os.read(output / "index.html")
    assert(html.contains("Codebase status"))
    assert(html.contains("Maximum layer depth"))
    assert(html.contains("https://cdn.jsdelivr.net/npm/d3@7.9.0"))
  }

  test("inspection reads the report cached by status") {
    assertEquals(runCli("status", "--config", config.toString, "--commit", "abc123").exitCode, 0)
    val result = runCli("inspect-cycle", "--config", config.toString, "--id", "scc:com.example.modules.module1", "--format", "json")
    assertEquals(result.exitCode, 0)
    assert(result.out.text().contains("\"members\""))
  }

  test("status rejects an unknown configured project") {
    val result = runCli("status", "--config", config.toString, "--project", "missing", "--commit", "abc123")
    assertEquals(result.exitCode, 1)
    assert(result.err.text().contains("unknown project: missing"))
  }
