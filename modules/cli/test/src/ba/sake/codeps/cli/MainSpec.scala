package ba.sake.codeps.cli

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.codeps.report.*
import ba.sake.tupson.{*, given}

class MainSpec extends munit.FunSuite:

  private def runCli(args: String*): os.CommandResult =
    runCliIn(os.pwd, args*)

  private def runCliIn(cwd: os.Path, args: String*): os.CommandResult =
    val command: Seq[os.Shellable] =
      Seq[os.Shellable](
        "java",
        "-cp",
        sys.props("java.class.path"),
        "ba.sake.codeps.cli.Main"
      ) ++ args.map(value => value: os.Shellable)
    os.proc(command).call(cwd = cwd, check = false, stderr = os.Pipe)

  private val config = os.pwd / "tmp" / "cli-test" / "status-config.yaml"
  private val history = os.pwd / ".codeps" / "fixture.ndjson"
  private val output = os.pwd / ".codeps" / "out" / "fixture"

  private def withSnapshotProject(git: Boolean)(body: os.Path => Unit): Unit =
    val repo = os.temp.dir(prefix = "codeps-snapshot-")
    try
      os.makeDir.all(repo / ".codeps")
      os.copy(os.pwd / "testFixtures" / "cyclic.json", repo / "graph.json")
      os.write(repo / ".gitignore", ".codeps/out/\n.codeps/*.ndjson\n")
      os.write(repo / ".codeps" / "config.yaml",
        "projects:\n  fixture:\n    source: export\n    inputs: [graph.json]\n    max-snapshot-age: off\n")
      if git then
        os.proc("git", "init", "-q").call(cwd = repo)
        os.proc("git", "add", ".").call(cwd = repo)
        os.proc("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com",
          "commit", "-qm", "fixture").call(cwd = repo,
          env = Map("GIT_AUTHOR_DATE" -> "2026-09-01T09:00:00Z", "GIT_COMMITTER_DATE" -> "2026-09-02T10:00:00Z"))
      body(repo)
    finally os.remove.all(repo)

  private def snapshot(repo: os.Path): HealthHistoryEntry =
    os.read.lines(repo / ".codeps" / "fixture.ndjson").last.parseJson[HealthHistoryEntry]

  test("clean snapshots use HEAD and its committer timestamp, keeping report generation time") {
    withSnapshotProject(git = true) { repo =>
      val head = os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
      val result = runCliIn(repo, "status")
      assertEquals(result.exitCode, 0, result.err.text())
      assertEquals(snapshot(repo).commit, head)
      assertEquals(snapshot(repo).at, java.time.Instant.parse("2026-09-02T10:00:00Z"))
      val report = os.read(repo / ".codeps" / "out" / "fixture" / "report.json").parseJson[MetricsReports]
      assert(report.packages.generatedAt.isAfter(snapshot(repo).at))
      val before = os.read(repo / ".codeps" / "fixture.ndjson")
      assertEquals(runCliIn(repo, "status").exitCode, 0)
      assertEquals(os.read(repo / ".codeps" / "fixture.ndjson"), before)
    }
  }

  test("unstaged, staged and untracked changes mark snapshots dirty with analysis time") {
    for change <- Seq("unstaged", "staged", "untracked") do
      withSnapshotProject(git = true) { repo =>
        val head = os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
        if change == "untracked" then os.write(repo / "new.txt", "new")
        else
          os.write.append(repo / ".gitignore", "\n# changed\n")
          if change == "staged" then os.proc("git", "add", ".gitignore").call(cwd = repo)
        val before = java.time.Instant.now()
        val result = runCliIn(repo, "status")
        assertEquals(result.exitCode, 0, result.err.text())
        assertEquals(snapshot(repo).commit, s"$head-dirty")
        assert(!snapshot(repo).at.isBefore(before))
        assert(!snapshot(repo).at.isAfter(java.time.Instant.now()))
      }
  }

  test("non-Git directories and unborn repositories use epoch milliseconds as snapshot IDs") {
    for unborn <- Seq(false, true) do
      withSnapshotProject(git = false) { repo =>
        if unborn then os.proc("git", "init", "-q").call(cwd = repo)
        val result = runCliIn(repo, "status")
        assertEquals(result.exitCode, 0, result.err.text())
        val entry = snapshot(repo)
        assertEquals(entry.commit, entry.at.toEpochMilli.toString)
      }
  }

  test("snapshot metadata overrides work independently") {
    withSnapshotProject(git = true) { repo =>
      val result = runCliIn(repo, "status", "--commit", "custom")
      assertEquals(result.exitCode, 0, result.err.text())
      assertEquals(snapshot(repo).commit, "custom")
      assertEquals(snapshot(repo).at, java.time.Instant.parse("2026-09-02T10:00:00Z"))
    }
    withSnapshotProject(git = false) { repo =>
      val at = java.time.Instant.parse("2026-09-04T10:00:00Z")
      val result = runCliIn(repo, "status", "--generated-at", at.toString)
      assertEquals(result.exitCode, 0, result.err.text())
      assertEquals(snapshot(repo).at, at)
      assertEquals(snapshot(repo).commit, at.toEpochMilli.toString)
    }
  }

  test("all projects share metadata resolved before history writes dirty the checkout") {
    withSnapshotProject(git = true) { repo =>
      os.write.over(repo / ".gitignore", ".codeps/out/\n")
      os.write.append(repo / ".codeps" / "config.yaml",
        "  other:\n    source: export\n    inputs: [graph.json]\n")
      os.proc("git", "add", ".").call(cwd = repo)
      os.proc("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com",
        "commit", "-qm", "second project").call(cwd = repo)
      val head = os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
      val result = runCliIn(repo, "status")
      assertEquals(result.exitCode, 0, result.err.text())
      val other = os.read.lines(repo / ".codeps" / "other.ndjson").last.parseJson[HealthHistoryEntry]
      assertEquals(snapshot(repo).commit, head)
      assertEquals(other.commit, head)
      assertEquals(other.at, snapshot(repo).at)
    }
  }

  override def beforeEach(context: BeforeEach): Unit =
    os.makeDir.all(config / os.up)
    os.write.over(
      config,
      """projects:
        |  fixture:
        |    source: export
        |    inputs: [testFixtures/cyclic.json]
        |    scope: packages
        |    max-snapshot-age: off
        |""".stripMargin
    )
    os.remove.all(history)
    os.remove.all(output)

  override def afterEach(context: AfterEach): Unit =
    os.remove.all(history)
    os.remove.all(output)

  test("status records a configured project and renders its dashboard") {
    val result = runCli(
      "status",
      "--config",
      config.toString,
      "--commit",
      "abc123",
      "--generated-at",
      "2026-09-04T10:00:00Z"
    )
    assertEquals(result.exitCode, 0)
    assert(os.exists(history))
    assert(os.exists(output / "report.json"))
    assert(os.exists(output / "index.html"))
    val html = os.read(output / "index.html")
    assert(html.contains("Codebase status"))
    assert(html.contains("Home"))
    assert(html.contains("combined health"))
    assert(html.contains("aria-selected"))
    assert(html.contains("commit-axis"))
    assert(!html.contains("Maximum layer depth"))
    assert(html.contains("https://cdn.jsdelivr.net/npm/d3@7.9.0"))
    val report = os.read(output / "report.json")
    assert(report.contains("\"packages\""))
    assert(report.contains("\"files\""))
    assert(!report.contains("\"scopes\""))
    assertEquals(ArtifactSchemas.errors("report.schema.json", report), Nil)
    os.read
      .lines(history)
      .foreach(line =>
        assertEquals(ArtifactSchemas.errors("history.schema.json", line), Nil)
      )
    val findings =
      runCli("findings", "--config", config.toString, "--format", "json")
    assertEquals(findings.exitCode, 0)
    assertEquals(
      ArtifactSchemas.errors("findings.schema.json", findings.out.text()),
      Nil
    )
    val selected = findings.out.text().parseJson[FindingsSelection]
    assert(selected.findings.forall(_.nextAction.contains("--id ")))
  }

  test("inspection reads the report cached by status") {
    assertEquals(
      runCli(
        "status",
        "--config",
        config.toString,
        "--commit",
        "abc123"
      ).exitCode,
      0
    )
    val result = runCli(
      "inspect-cycle",
      "--config",
      config.toString,
      "--id",
      "scc:com.example.modules.module1",
      "--format",
      "json"
    )
    assertEquals(result.exitCode, 0)
    assert(result.out.text().contains("\"members\""))
    assertEquals(
      ArtifactSchemas.errors("inspect-cycle.schema.json", result.out.text()),
      Nil
    )
    val node = runCli(
      "inspect-node",
      "--config",
      config.toString,
      "--id",
      "com.example.app",
      "--format",
      "json"
    )
    assertEquals(node.exitCode, 0)
    assertEquals(
      ArtifactSchemas.errors("inspect-node.schema.json", node.out.text()),
      Nil
    )
  }

  private def cacheFindings(): MetricsReports =
    val rows = (1 to 25).map(i =>
      Finding(
        id = s"finding:$i",
        kind = if i % 2 == 0 then "cycle" else "propagator",
        severity = if i <= 15 then "high" else "low",
        subject = s"node:$i",
        evidence = s"evidence=$i",
        confidence = "high",
        nextAction = s"inspect-node --id node:$i"
      )
    )
    val packages = ScopeReport(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-10-12T10:00:00Z"),
      summary = Summary(nodes = 0, edges = 0, nodesInCycles = 0, orphans = 0),
      cycles = Nil,
      propagators = Nil,
      surface = Nil,
      orphans = Nil,
      findings = rows,
      truncation = Some(ReportTruncation(findingsOmitted = 7)),
      schemaVersion = 3
    )
    val reports = MetricsReports(
      packages = packages,
      files = Some(
        packages
          .copy(scope = "files", findings = rows.reverse, truncation = None)
      ),
      schemaVersion = 4
    )
    os.makeDir.all(output)
    os.write.over(output / "report.json", reports.toJson)
    reports

  test(
    "findings bounds cached matches in report order without rewriting artifacts"
  ) {
    val reports = cacheFindings()
    val before = os.read(output / "report.json")
    val result =
      runCli("findings", "--config", config.toString, "--format", "json")
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

  test(
    "findings filters before limiting, supports file scope and empty selections"
  ) {
    val reports = cacheFindings()
    val result = runCli(
      "findings",
      "--config",
      config.toString,
      "--scope",
      "files",
      "--severity",
      "high",
      "--kind",
      "cycle",
      "--limit",
      "2",
      "--format",
      "json"
    )
    assertEquals(result.exitCode, 0)
    val selection = result.out.text().parseJson[FindingsSelection]
    val matches = reports.files.get.findings.filter(f =>
      f.severity == "high" && f.kind == "cycle"
    )
    assertEquals(selection.findings, matches.take(2))
    assertEquals(selection.total, 7)
    assertEquals(selection.omitted, 5)
    assertEquals(selection.sourceFindingsOmitted, 0)
    assertEquals(selection.scope, "files")
    val empty = runCli(
      "findings",
      "--config",
      config.toString,
      "--severity",
      "critical",
      "--format",
      "json"
    )
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
    for args <- Seq(
        Seq("--limit", "0"),
        Seq("--limit", "10001"),
        Seq("--limit", "-1"),
        Seq("--severity", "urgent"),
        Seq("--kind", "typo"),
        Seq("--scope", "symbols")
      )
    do
      val result = runCli(
        (Seq(
          "findings",
          "--config",
          config.toString,
          "--format",
          "json"
        ) ++ args)*
      )
      assertEquals(result.exitCode, 1)
      assertEquals(result.out.text(), "")
      assert(result.err.text().nonEmpty)
  }

  test("findings requires a cached report and available scope") {
    val missing =
      runCli("findings", "--config", config.toString, "--format", "json")
    assertEquals(missing.exitCode, 1)
    assertEquals(missing.out.text(), "")
    assert(missing.err.text().contains("report path does not exist"))
    val reports = cacheFindings()
    os.write.over(output / "report.json", reports.copy(files = None).toJson)
    val files =
      runCli("findings", "--config", config.toString, "--scope", "files")
    assertEquals(files.exitCode, 1)
    assert(files.err.text().contains("file metrics are unavailable"))
  }

  test("findings requires project selection with multiple projects") {
    cacheFindings()
    os.write.append(
      config,
      "  other:\n    source: export\n    inputs: [testFixtures/cyclic.json]\n"
    )
    val ambiguous = runCli("findings", "--config", config.toString)
    assertEquals(ambiguous.exitCode, 1)
    val selected = runCli(
      "findings",
      "--config",
      config.toString,
      "--project",
      "fixture",
      "--format",
      "json"
    )
    assertEquals(selected.exitCode, 0)
    assertEquals(
      selected.out.text().parseJson[FindingsSelection].project,
      "fixture"
    )
  }

  test(
    "cached commands do not create starter configs or mix diagnostics into JSON"
  ) {
    val repo = os.pwd / "tmp" / "cli-test" / "read-only-config"
    os.makeDir.all(repo)
    assertEquals(
      os.proc("git", "init", "-q").call(cwd = repo, check = false).exitCode,
      0
    )
    for args <- Seq(
        Seq("findings", "--format", "json"),
        Seq("inspect-node", "--id", "a", "--format", "json")
      )
    do
      val result = runCliIn(repo, args*)
      assertEquals(result.exitCode, 1)
      assertEquals(result.out.text(), "")
      assert(!os.exists(repo / ".codeps" / "config.yaml"))
  }

  test("status rejects an unknown configured project") {
    val result = runCli(
      "status",
      "--config",
      config.toString,
      "--project",
      "missing",
      "--commit",
      "abc123"
    )
    assertEquals(result.exitCode, 1)
    assert(result.err.text().contains("unknown project: missing"))
  }

  test("status creates a starter config when the repository has none") {
    val repo = os.pwd / "tmp" / "cli-test" / "starter-config"
    os.remove.all(repo)
    os.makeDir.all(repo)
    assertEquals(
      os.proc("git", "init", "-q").call(cwd = repo, check = false).exitCode,
      0
    )

    val result = runCliIn(repo, "status", "--commit", "abc123")
    assertEquals(
      result.exitCode,
      1
    ) // No SemanticDB is expected in an empty repository.
    val generated = repo / ".codeps" / "config.yaml"
    assert(os.exists(generated))
    assertEquals(
      os.read(generated),
      """# codeps analyzes this repository as one project by default.
        |projects:
        |  root:
        |    root: .
        |    source: semanticdb
        |    inputs: [.]
        |    # Package and file metrics are both emitted when the source contains files.
        |""".stripMargin
    )
  }
