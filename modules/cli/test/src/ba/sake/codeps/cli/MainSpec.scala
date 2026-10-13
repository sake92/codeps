package ba.sake.codeps.cli

import ba.sake.codeps.report.*
import ba.sake.tupson.{*, given}

class MainSpec extends munit.FunSuite:

  private def runCli(args: String*): os.CommandResult =
    runCliIn(os.pwd, args*)

  private def runCliIn(cwd: os.Path, args: String*): os.CommandResult =
    val command: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-cp", sys.props("java.class.path"), "ba.sake.codeps.cli.Main") ++ args.map(value => value: os.Shellable)
    os.proc(command).call(cwd = cwd, check = false, stderr = os.Pipe)

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
    assert(html.contains("Home"))
    assert(html.contains("combined health"))
    assert(html.contains("aria-selected"))
    assert(html.contains("%b %-d, %H:%M UTC"))
    assert(!html.contains("Maximum layer depth"))
    assert(html.contains("https://cdn.jsdelivr.net/npm/d3@7.9.0"))
    val report = os.read(output / "report.json")
    assert(report.contains("\"packages\""))
    assert(report.contains("\"files\""))
    assert(!report.contains("\"scopes\""))
    assertEquals(ArtifactSchemas.errors("report.schema.json", report), Nil)
    os.read.lines(history).foreach(line => assertEquals(ArtifactSchemas.errors("history.schema.json", line), Nil))
    val findings = runCli("findings", "--config", config.toString, "--format", "json")
    assertEquals(findings.exitCode, 0)
    assertEquals(ArtifactSchemas.errors("findings.schema.json", findings.out.text()), Nil)
    val selected = findings.out.text().parseJson[FindingsSelection]
    assert(selected.findings.forall(_.nextAction.contains("--id ")))
  }

  test("inspection reads the report cached by status") {
    assertEquals(runCli("status", "--config", config.toString, "--commit", "abc123").exitCode, 0)
    val result = runCli("inspect-cycle", "--config", config.toString, "--id", "scc:com.example.modules.module1", "--format", "json")
    assertEquals(result.exitCode, 0)
    assert(result.out.text().contains("\"members\""))
    assertEquals(ArtifactSchemas.errors("inspect-cycle.schema.json", result.out.text()), Nil)
    val node = runCli("inspect-node", "--config", config.toString, "--id", "com.example.app", "--format", "json")
    assertEquals(node.exitCode, 0)
    assertEquals(ArtifactSchemas.errors("inspect-node.schema.json", node.out.text()), Nil)
  }

  private def cacheFindings(): MetricsReports =
    val rows = (1 to 25).map(i => Finding(s"finding:$i", if i % 2 == 0 then "cycle" else "propagator",
      if i <= 15 then "high" else "low", s"node:$i", s"evidence=$i", "high", s"inspect-node --id node:$i"))
    val packages = MetricsReport("packages", "2026-10-12T10:00:00Z", Summary(0, 0, 0, 0),
      Nil, Nil, Nil, Nil, findings = rows, truncation = Some(ReportTruncation(7)))
    val reports = MetricsReports(packages, Some(packages.copy(scope = "files", findings = rows.reverse, truncation = None)))
    os.makeDir.all(output)
    os.write.over(output / "report.json", reports.toJson)
    reports

  test("findings bounds cached matches in report order without rewriting artifacts") {
    val reports = cacheFindings()
    val before = os.read(output / "report.json")
    val result = runCli("findings", "--config", config.toString, "--format", "json")
    assertEquals(result.exitCode, 0)
    assertEquals(result.err.text(), "")
    val selection = result.out.text().parseJson[FindingsSelection]
    assertEquals(selection.project, "fixture")
    assertEquals(selection.scope, "packages")
    assertEquals(selection.generatedAt, reports.packages.generatedAt)
    assertEquals(selection.findings, reports.packages.findings.take(10))
    assertEquals(selection.total, 25)
    assertEquals(selection.omitted, 15)
    assertEquals(selection.sourceFindingsOmitted, 7)
    assertEquals(os.read(output / "report.json"), before)
    assert(!os.exists(history))
    assert(!os.exists(output / "index.html"))
  }

  test("findings filters before limiting, supports file scope and empty selections") {
    val reports = cacheFindings()
    val result = runCli("findings", "--config", config.toString, "--scope", "files",
      "--severity", "high", "--kind", "cycle", "--limit", "2", "--format", "json")
    assertEquals(result.exitCode, 0)
    val selection = result.out.text().parseJson[FindingsSelection]
    val matches = reports.files.get.findings.filter(f => f.severity == "high" && f.kind == "cycle")
    assertEquals(selection.findings, matches.take(2))
    assertEquals(selection.total, 7)
    assertEquals(selection.omitted, 5)
    assertEquals(selection.sourceFindingsOmitted, 0)
    assertEquals(selection.scope, "files")
    val empty = runCli("findings", "--config", config.toString, "--severity", "critical", "--format", "json")
    assertEquals(empty.exitCode, 0)
    val noMatches = empty.out.text().parseJson[FindingsSelection]
    assertEquals(noMatches.findings, Nil)
    assertEquals(noMatches.total, 0)
    assertEquals(noMatches.omitted, 0)
    assertEquals(noMatches.sourceFindingsOmitted, 7)
    val table = runCli("findings", "--config", config.toString, "--limit", "1")
    assertEquals(table.exitCode, 0)
    assert(table.out.text().contains("omitted=24, sourceFindingsOmitted=7"))
  }

  test("findings rejects invalid filters and limits with clean stdout") {
    cacheFindings()
    for args <- Seq(Seq("--limit", "0"), Seq("--limit", "10001"), Seq("--limit", "-1"),
      Seq("--severity", "urgent"), Seq("--kind", "typo"), Seq("--scope", "symbols")) do
      val result = runCli((Seq("findings", "--config", config.toString, "--format", "json") ++ args)*)
      assertEquals(result.exitCode, 1)
      assertEquals(result.out.text(), "")
      assert(result.err.text().nonEmpty)
  }

  test("findings requires a cached report and available scope") {
    val missing = runCli("findings", "--config", config.toString, "--format", "json")
    assertEquals(missing.exitCode, 1)
    assertEquals(missing.out.text(), "")
    assert(missing.err.text().contains("report path does not exist"))
    val reports = cacheFindings()
    os.write.over(output / "report.json", reports.copy(files = None).toJson)
    val files = runCli("findings", "--config", config.toString, "--scope", "files")
    assertEquals(files.exitCode, 1)
    assert(files.err.text().contains("file metrics are unavailable"))
  }

  test("findings requires project selection with multiple projects") {
    cacheFindings()
    os.write.append(config, "  other:\n    source: export\n    inputs: [testFixtures/cyclic.json]\n")
    val ambiguous = runCli("findings", "--config", config.toString)
    assertEquals(ambiguous.exitCode, 1)
    val selected = runCli("findings", "--config", config.toString, "--project", "fixture", "--format", "json")
    assertEquals(selected.exitCode, 0)
    assertEquals(selected.out.text().parseJson[FindingsSelection].project, "fixture")
  }

  test("cached commands do not create starter configs or mix diagnostics into JSON") {
    val repo = os.pwd / "tmp" / "cli-test" / "read-only-config"
    os.makeDir.all(repo)
    assertEquals(os.proc("git", "init", "-q").call(cwd = repo, check = false).exitCode, 0)
    for args <- Seq(Seq("findings", "--format", "json"), Seq("inspect-node", "--id", "a", "--format", "json")) do
      val result = runCliIn(repo, args*)
      assertEquals(result.exitCode, 1)
      assertEquals(result.out.text(), "")
      assert(!os.exists(repo / ".codeps" / "config.yaml"))
  }

  test("status rejects an unknown configured project") {
    val result = runCli("status", "--config", config.toString, "--project", "missing", "--commit", "abc123")
    assertEquals(result.exitCode, 1)
    assert(result.err.text().contains("unknown project: missing"))
  }

  test("status creates a starter config when the repository has none") {
    val repo = os.pwd / "tmp" / "cli-test" / "starter-config"
    os.remove.all(repo)
    os.makeDir.all(repo)
    assertEquals(os.proc("git", "init", "-q").call(cwd = repo, check = false).exitCode, 0)

    val result = runCliIn(repo, "status", "--commit", "abc123")
    assertEquals(result.exitCode, 1) // No SemanticDB is expected in an empty repository.
    val generated = repo / ".codeps" / "config.yaml"
    assert(os.exists(generated))
    assertEquals(os.read(generated),
      """# codeps analyzes this repository as one project by default.
        |projects:
        |  root:
        |    root: .
        |    source: semanticdb
        |    inputs: [.]
        |    # Package and file metrics are both emitted when the source contains files.
        |""".stripMargin)
  }
