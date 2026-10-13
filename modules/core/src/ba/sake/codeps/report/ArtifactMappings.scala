package ba.sake.codeps.report

import java.time.Instant
import ba.sake.codeps.artifacts.ArtifactCodec
import ba.sake.codeps.artifacts.models.*
import ba.sake.tupson.{*, given}
import org.typelevel.jawn.ast.{JString, JValue}

/** Explicit conversions keep graph calculations independent of the generated
  * wire types.
  */
private[codeps] object ArtifactMappings {
  type WSummary = `Shared codeps artifact definitions__summary`
  type WCycle = `Shared codeps artifact definitions__cycle`
  type WSurfaceRow = `Shared codeps artifact definitions__surface`
  type WPropagatorRow = `Shared codeps artifact definitions__propagator`
  type WFinding = `Shared codeps artifact definitions__finding`
  type WCutAnalysis = `Shared codeps artifact definitions__cutAnalysis`
  type WCutCandidate = `Shared codeps artifact definitions__cut`
  type WMetricsReport = `Shared codeps artifact definitions__scopeReport`
  type WMetricsReports = `Codeps metrics report v4`
  type WFindingsSelection = `Codeps bounded findings selection v1`
  type WNodeInspection = `Codeps node inspection`
  type WCycleInspection = `Codeps cycle inspection`
  type WHealthSnapshot = `Shared codeps artifact definitions__healthSnapshot`
  type WHealthHistoryEntry = `Codeps health history entry v6`

  def toSummary(v: Summary): WSummary =
    `Shared codeps artifact definitions__summary`(
      nodes = v.nodes.toLong,
      edges = v.edges.toLong,
      nodesInCycles = v.nodesInCycles.toLong,
      orphans = v.orphans.toLong
    )
  def fromSummary(v: WSummary): Summary = Summary(
    nodes = Math.toIntExact(v.nodes),
    edges = Math.toIntExact(v.edges),
    nodesInCycles = Math.toIntExact(v.nodesInCycles),
    orphans = Math.toIntExact(v.orphans)
  )

  def toCycle(v: Cycle): WCycle = `Shared codeps artifact definitions__cycle`(
    id = v.id,
    members = v.members,
    witnessCycle = v.witnessCycle,
    size = v.size.toLong,
    extFanIn = v.extFanIn.toLong,
    internalEdges = v.internalEdges.toLong,
    incomingEdges = v.incomingEdges.toLong,
    outgoingEdges = v.outgoingEdges.toLong,
    cutAnalysis = toCutAnalysis(v.cutAnalysis)
  )
  def fromCycle(v: WCycle): Cycle = Cycle(
    id = v.id,
    members = v.members,
    witnessCycle = v.witnessCycle,
    size = Math.toIntExact(v.size),
    extFanIn = Math.toIntExact(v.extFanIn),
    internalEdges = Math.toIntExact(v.internalEdges),
    incomingEdges = Math.toIntExact(v.incomingEdges),
    outgoingEdges = Math.toIntExact(v.outgoingEdges),
    cutAnalysis = fromCutAnalysis(v.cutAnalysis)
  )

  def toSurfaceRow(v: SurfaceRow): WSurfaceRow =
    `Shared codeps artifact definitions__surface`(
      node = v.node,
      fanIn = v.fanIn.toLong,
      fanOut = v.fanOut.toLong,
      ports = v.ports,
      mutPorts = v.mutPorts,
      exposure = v.exposure,
      publicSurface = v.publicSurface,
      protectedSurface = v.protectedSurface,
      packageSurface = v.packageSurface,
      privateSurface = v.privateSurface,
      publicMutableSurface = v.publicMutableSurface,
      protectedMutableSurface = v.protectedMutableSurface,
      packageMutableSurface = v.packageMutableSurface,
      privateMutableSurface = v.privateMutableSurface,
      totalDeclaredSurface = v.totalDeclaredSurface,
      dependentsPerPublicPort = v.dependentsPerPublicPort,
      cycleId = v.cycleId,
      encapsulationRatio = v.encapsulationRatio,
      publicMutableRatio = v.publicMutableRatio
    )
  def fromSurfaceRow(v: WSurfaceRow): SurfaceRow = SurfaceRow(
    node = v.node,
    fanIn = Math.toIntExact(v.fanIn),
    fanOut = Math.toIntExact(v.fanOut),
    ports = v.ports,
    mutPorts = v.mutPorts,
    exposure = v.exposure,
    publicSurface = v.publicSurface,
    protectedSurface = v.protectedSurface,
    packageSurface = v.packageSurface,
    privateSurface = v.privateSurface,
    publicMutableSurface = v.publicMutableSurface,
    protectedMutableSurface = v.protectedMutableSurface,
    packageMutableSurface = v.packageMutableSurface,
    privateMutableSurface = v.privateMutableSurface,
    totalDeclaredSurface = v.totalDeclaredSurface,
    dependentsPerPublicPort = v.dependentsPerPublicPort,
    cycleId = v.cycleId,
    encapsulationRatio = v.encapsulationRatio,
    publicMutableRatio = v.publicMutableRatio
  )

  def toPropagatorRow(v: PropagatorRow): WPropagatorRow =
    `Shared codeps artifact definitions__propagator`(
      node = v.node,
      fanIn = v.fanIn.toLong,
      fanOut = v.fanOut.toLong,
      score = v.score
    )
  def fromPropagatorRow(v: WPropagatorRow): PropagatorRow = PropagatorRow(
    node = v.node,
    fanIn = Math.toIntExact(v.fanIn),
    fanOut = Math.toIntExact(v.fanOut),
    score = v.score
  )

  def toFinding(v: Finding): WFinding =
    `Shared codeps artifact definitions__finding`(
      id = v.id,
      kind =
        literal["cycle" | "propagator" | "mutableSurface" | "structuralUse"](
          v.kind
        ),
      severity = literal["critical" | "high" | "medium" | "low"](v.severity),
      subject = v.subject,
      evidence = v.evidence,
      confidence = literal["high" | "structuralProxy"](v.confidence),
      nextAction = v.nextAction
    )
  def fromFinding(v: WFinding): Finding = Finding(
    id = v.id,
    kind = v.kind,
    severity = v.severity,
    subject = v.subject,
    evidence = v.evidence,
    confidence = v.confidence,
    nextAction = v.nextAction
  )

  def toFindingsSelection(v: FindingsSelection): WFindingsSelection =
    `Codeps bounded findings selection v1`(
      schemaVersion = 1,
      project = v.project,
      scope = literal["packages" | "files"](v.scope),
      generatedAt = Instant.parse(v.generatedAt),
      total = v.total.toLong,
      omitted = v.omitted.toLong,
      sourceFindingsOmitted = v.sourceFindingsOmitted.toLong,
      findings = v.findings.map(toFinding)
    )
  def fromFindingsSelection(v: WFindingsSelection): FindingsSelection =
    FindingsSelection(
      schemaVersion = v.schemaVersion,
      project = v.project,
      scope = v.scope,
      generatedAt = v.generatedAt.toString,
      total = Math.toIntExact(v.total),
      omitted = Math.toIntExact(v.omitted),
      sourceFindingsOmitted = Math.toIntExact(v.sourceFindingsOmitted),
      findings = v.findings.map(fromFinding)
    )

  def toNodeInspection(v: NodeInspection): WNodeInspection =
    `Codeps node inspection`(
      id = v.id,
      surface = toSurfaceRow(v.surface),
      cycleId = v.cycleId,
      findings = v.findings.map(toFinding)
    )
  def fromNodeInspection(v: WNodeInspection): NodeInspection = NodeInspection(
    id = v.id,
    surface = fromSurfaceRow(v.surface),
    cycleId = v.cycleId,
    findings = v.findings.map(fromFinding)
  )

  def toCycleInspection(v: CycleInspection): WCycleInspection =
    `Codeps cycle inspection`(
      id = v.id,
      members = v.members,
      witnessCycle = v.witnessCycle,
      size = v.size.toLong,
      extFanIn = v.extFanIn.toLong,
      internalEdges = v.internalEdges.toLong,
      incomingEdges = v.incomingEdges.toLong,
      outgoingEdges = v.outgoingEdges.toLong,
      cutAnalysis = toCutAnalysis(v.cutAnalysis),
      findings = v.findings.map(toFinding)
    )
  def fromCycleInspection(v: WCycleInspection): CycleInspection =
    CycleInspection(
      id = v.id,
      members = v.members,
      witnessCycle = v.witnessCycle,
      size = Math.toIntExact(v.size),
      extFanIn = Math.toIntExact(v.extFanIn),
      internalEdges = Math.toIntExact(v.internalEdges),
      incomingEdges = Math.toIntExact(v.incomingEdges),
      outgoingEdges = Math.toIntExact(v.outgoingEdges),
      cutAnalysis = fromCutAnalysis(v.cutAnalysis),
      findings = v.findings.map(fromFinding)
    )

  def toCutCandidate(v: CutCandidate): WCutCandidate =
    `Shared codeps artifact definitions__cut`(
      Seq(v.source, v.target),
      v.weight.toLong
    )
  def fromCutCandidate(v: WCutCandidate): CutCandidate = {
    require(v.edge.size == 2, "cut edge must contain exactly two node ids")
    CutCandidate(v.edge.head, v.edge(1), Math.toIntExact(v.weight))
  }

  def toCutAnalysis(v: CutAnalysis): WCutAnalysis =
    `Shared codeps artifact definitions__cutAnalysis`(
      literal[
        "notRequested" | "completedExact" | "completedHeuristic" |
          "budgetExceeded"
      ](v.status),
      v.greedyCutEstimate.map(_.toLong),
      v.solutions.map(s => (cuts = s.cuts.map(toCutCandidate))),
      v.examinedCandidates.toLong
    )
  def fromCutAnalysis(v: WCutAnalysis): CutAnalysis =
    CutAnalysis(
      v.status,
      v.greedyCutEstimate.map(Math.toIntExact),
      v.solutions.map(s => Solution(s.cuts.map(fromCutCandidate))),
      Math.toIntExact(v.examinedCandidates)
    )

  def toMetricsReport(v: MetricsReport): WMetricsReport = {
    val omitted = math.max(0, v.findings.size - 10000)
    val truncation =
      v.truncation.orElse(if omitted > 0 then Some(ReportTruncation(omitted))
      else None)
    `Shared codeps artifact definitions__scopeReport`(
      3,
      literal["packages" | "files"](v.scope),
      Instant.parse(v.generatedAt),
      toSummary(v.summary),
      v.cycles.map(toCycle),
      v.propagators.map(toPropagatorRow),
      v.surface.map(toSurfaceRow),
      v.orphans,
      v.findings.take(10000).map(toFinding),
      truncation.map(t => (findingsOmitted = t.findingsOmitted.toLong))
    )
  }
  def fromMetricsReport(v: WMetricsReport): MetricsReport =
    MetricsReport(
      v.scope,
      v.generatedAt.toString,
      fromSummary(v.summary),
      v.cycles.map(fromCycle),
      v.propagators.map(fromPropagatorRow),
      v.surface.map(fromSurfaceRow),
      v.orphans,
      3,
      v.findings.map(fromFinding),
      v.truncation.map(t =>
        ReportTruncation(Math.toIntExact(t.findingsOmitted))
      )
    )

  private def packageReport(
      v: MetricsReport
  ): `Codeps metrics report v4__packages` = {
    require(v.scope == "packages", "expected package report")
    val w = toMetricsReport(v)
    `Codeps metrics report v4__packages`(
      3,
      "packages",
      w.generatedAt,
      w.summary,
      w.cycles,
      w.propagators,
      w.surface,
      w.orphans,
      w.findings,
      w.truncation
    )
  }
  private def fileReport(
      v: MetricsReport
  ): `Codeps metrics report v4__files` = {
    require(v.scope == "files", "expected file report")
    val w = toMetricsReport(v)
    `Codeps metrics report v4__files`(
      3,
      "files",
      w.generatedAt,
      w.summary,
      w.cycles,
      w.propagators,
      w.surface,
      w.orphans,
      w.findings,
      w.truncation
    )
  }
  def toMetricsReports(v: MetricsReports): WMetricsReports =
    `Codeps metrics report v4`(
      schemaVersion = 4,
      files = v.files.map(fileReport),
      packages = packageReport(v.packages)
    )
  def fromMetricsReports(v: WMetricsReports): MetricsReports = {
    val p = v.packages
    val packages = fromMetricsReport(
      `Shared codeps artifact definitions__scopeReport`(
        3,
        "packages",
        p.generatedAt,
        p.summary,
        p.cycles,
        p.propagators,
        p.surface,
        p.orphans,
        p.findings,
        p.truncation
      )
    )
    val files = v.files.map(f =>
      fromMetricsReport(
        `Shared codeps artifact definitions__scopeReport`(
          3,
          "files",
          f.generatedAt,
          f.summary,
          f.cycles,
          f.propagators,
          f.surface,
          f.orphans,
          f.findings,
          f.truncation
        )
      )
    )
    MetricsReports(packages, files)
  }

  private type HealthStatus = "critical" | "unhealthy" | "needs-attention" |
    "healthy" | "excellent"
  def toHealthSnapshot(v: HealthSnapshot): WHealthSnapshot =
    `Shared codeps artifact definitions__healthSnapshot`(
      literal[HealthStatus](v.status),
      (
        score = v.health.score,
        status = literal[HealthStatus](v.health.status),
        factors = (
          cycles = v.health.factors.cycles,
          mutableSurface = v.health.factors.mutableSurface,
          exposedSurface = v.health.factors.exposedSurface,
          structuralUse = v.health.factors.structuralUse,
          propagators = v.health.factors.propagators
        )
      ),
      (nodes = v.structure.nodes.toLong, edges = v.structure.edges.toLong),
      (
        count = v.cycles.count.toLong,
        nodes = v.cycles.nodes.toLong,
        largestScc = v.cycles.largestScc.toLong,
        internalEdges = v.cycles.internalEdges.toLong
      ),
      `Shared codeps artifact definitions__healthSnapshot__surface`(
        v.surface.publicSurface,
        v.surface.publicMutableSurface,
        v.surface.totalDeclaredSurface,
        v.surface.encapsulationRatio
      ),
      (
        critical = v.findings.critical.toLong,
        high = v.findings.high.toLong,
        medium = v.findings.medium.toLong,
        low = v.findings.low.toLong
      )
    )
  def fromHealthSnapshot(v: WHealthSnapshot): HealthSnapshot =
    HealthSnapshot(
      v.status,
      HealthScore(
        v.health.score,
        v.health.status,
        HealthFactors(
          v.health.factors.cycles,
          v.health.factors.mutableSurface,
          v.health.factors.exposedSurface,
          v.health.factors.structuralUse,
          v.health.factors.propagators
        )
      ),
      HealthStructure(
        Math.toIntExact(v.structure.nodes),
        Math.toIntExact(v.structure.edges)
      ),
      HealthCycles(
        Math.toIntExact(v.cycles.count),
        Math.toIntExact(v.cycles.nodes),
        Math.toIntExact(v.cycles.largestScc),
        Math.toIntExact(v.cycles.internalEdges)
      ),
      HealthSurface(
        v.surface.publicSurface,
        v.surface.publicMutableSurface,
        v.surface.totalDeclaredSurface,
        v.surface.encapsulationRatio
      ),
      HealthFindings(
        Math.toIntExact(v.findings.critical),
        Math.toIntExact(v.findings.high),
        Math.toIntExact(v.findings.medium),
        Math.toIntExact(v.findings.low)
      )
    )
  def toHealthHistoryEntry(v: HealthHistoryEntry): WHealthHistoryEntry =
    `Codeps health history entry v6`(
      6,
      Instant.parse(v.at),
      v.commit,
      toHealthSnapshot(v.packages),
      v.files.map(toHealthSnapshot)
    )
  def fromHealthHistoryEntry(v: WHealthHistoryEntry): HealthHistoryEntry =
    HealthHistoryEntry(
      v.at.toString,
      v.commit,
      fromHealthSnapshot(v.packages),
      v.files.map(fromHealthSnapshot)
    )

  private def literal[T: JsonRW](value: String): T =
    JsonRW[T].parse("$", JString(value))
}
