package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

class HealthHistorySpec extends munit.FunSuite:

  private def snapshot(nodes: Int = 100, ratio: Option[Double] = Some(0.2)) =
    HealthSnapshot(
      at = "2026-09-02T12:00:00Z",
      commit = "abc123",
      status = "healthy",
      structure = HealthStructure(nodes, 200, 4),
      cycles = HealthCycles(1, 2, 2, 2),
      surface = HealthSurface(2, 0, 10, ratio),
      findings = HealthFindings(0, 0, 0, 0)
    )

  test("snapshot JSON round-trips with the compact overall sections") {
    val value = snapshot()
    val json = value.toJson(spaces = 0, sort = true)
    assert(json.contains("\"schemaVersion\":1"))
    assert(json.contains("\"criticalPathLength\":4"))
    assert(json.contains("\"encapsulationRatio\":0.2"))
    assertEquals(json.parseJson[HealthSnapshot], value)
  }

  test("history comparison uses a strict relative threshold and zero crossings") {
    val previous = snapshot()
    assertEquals(HealthHistory.decision(Some(previous), snapshot(nodes = 101), 0.01, false), HealthRecordingDecision.NotSignificant)
    val changed = HealthHistory.decision(Some(previous), snapshot(nodes = 102), 0.01, false)
    assertEquals(changed, HealthRecordingDecision.Significant(Seq("structure.nodes")))
    val zeroCrossing = previous.copy(findings = HealthFindings(1, 0, 0, 0), status = "critical")
    assertEquals(HealthHistory.decision(Some(zeroCrossing), previous, 0.01, false), HealthRecordingDecision.Significant(Seq("findings.critical")))
  }

  test("checkpoint records an otherwise unchanged snapshot") {
    assertEquals(HealthHistory.decision(Some(snapshot()), snapshot(), 0.01, true), HealthRecordingDecision.Checkpoint)
  }

  test("NDJSON parser reports the failing line") {
    val error = HealthHistory.parseNdjson("{}\nnot-json\n").swap.toOption.get
    assert(error.contains("line 1"))
  }
