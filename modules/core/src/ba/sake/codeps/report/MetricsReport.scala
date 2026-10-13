package ba.sake.codeps.report

import ba.sake.tupson.JsonRW
import ba.sake.codeps.artifacts.ArtifactCodec
import ArtifactMappings.*

/** The flat metrics report — codeps's only user-facing output (v2.md §5).
  * Field names are camelCase; every value is derived fresh from the scope's
  * node/edge list on each run, never cached. */
case class MetricsReport(
    scope: String, // "packages" | "files"
    generatedAt: String, // ISO8601, UTC, second precision (trailing Z)
    summary: Summary,
    cycles: Seq[Cycle],
    propagators: Seq[PropagatorRow],
    surface: Seq[SurfaceRow],
    orphans: Seq[String],
    schemaVersion: Int = 3,
    findings: Seq[Finding] = Nil,
    /** Present when the default JSON inventory limit omitted rows. */
    truncation: Option[ReportTruncation] = None
)

case class Summary(
    nodes: Int,
    edges: Int,
    nodesInCycles: Int,
    orphans: Int
)

/** One cycle (multi-member SCC). `id` = "scc:" + smallest member id — stable
  * across recomputations after cuts, unlike a counter. `members` is exhaustive
  * sorted SCC membership; `witnessCycle` is one deterministic closed cycle
  * path through the smallest member. */
case class Cycle(
    id: String,
    members: Seq[String],
    size: Int,
    extFanIn: Int,
    cutAnalysis: CutAnalysis = CutAnalysis.notRequested,
    /** Number of edges whose source and target are both in this SCC. */
    internalEdges: Int = 0,
    witnessCycle: Seq[String] = Nil,
    /** Number of edges entering this SCC from outside it. Equal to extFanIn. */
    incomingEdges: Int = 0,
    /** Number of edges leaving this SCC to outside it. */
    outgoingEdges: Int = 0
)

/** One complete way to break the cycle: removing ALL `cuts` together dissolves
  * the cycle (no multi-member component remains among its members). A solution
  * may be 1 edge or several — dense interlocking cycles often need 2-3. */
case class Solution(cuts: Seq[CutCandidate])

/** An internal edge whose removal resolves the cycle for its endpoints (they end
  * up in no multi-member component); a leftover cycle elsewhere in the SCC does
  * not count. */
case class CutCandidate(source: String, target: String, weight: Int)

case class SurfaceRow(
    node: String,
    fanIn: Int,
    fanOut: Int,
    ports: Double,
    mutPorts: Double,
    exposure: Double,
    dependentsPerPublicPort: Option[Double],
    cycleId: Option[String] = None,
    publicSurface: Double = 0.0,
    protectedSurface: Double = 0.0,
    packageSurface: Double = 0.0,
    privateSurface: Double = 0.0,
    publicMutableSurface: Double = 0.0,
    protectedMutableSurface: Double = 0.0,
    packageMutableSurface: Double = 0.0,
    privateMutableSurface: Double = 0.0,
    totalDeclaredSurface: Double = 0.0,
    encapsulationRatio: Option[Double] = None,
    publicMutableRatio: Option[Double] = None
):
  /** Source-compatibility alias for consumers of pre-v2.1 reports. It is not
    * serialized; `dependentsPerPublicPort` is the unambiguous structural proxy. */
  def utilization: Option[Double] = dependentsPerPublicPort
  def packageRestrictedSurface: Double = packageSurface
  def packageRestrictedMutableSurface: Double = packageMutableSurface
  def privateMembersSurface: Double = privateSurface
  def privateMembersMutableSurface: Double = privateMutableSurface

/** Counts rows omitted from the bounded JSON report inventories. A missing
  * value means that the findings inventory was truncated. */
case class ReportTruncation(
    findingsOmitted: Int
)

/** A node that propagates changes to an above-average part of the graph.
  * `score` = (fanIn/avgFanIn + fanOut/avgFanOut) / 2 — an exactly average node
  * scores 1.0; only nodes above 1.0 are listed. The table presentation applies
  * its own top-10 bound; JSON applies a larger explicit bound and records
  * omissions in `truncation`. */
case class PropagatorRow(node: String, fanIn: Int, fanOut: Int, score: Double)

/** A stable, structured diagnostic derived from the report index. `evidence` is
  * intentionally human-readable while the subject and id remain machine-stable.
  * Confidence values distinguish direct graph evidence from structural proxies. */
case class Finding(
    id: String,
    kind: String,
    severity: String,
    subject: String,
    evidence: String,
    confidence: String,
    nextAction: String
)

object MetricsReport:
  private[report] val maxJsonFindings = 10000
  given JsonRW[MetricsReport] = ArtifactCodec.mapped[MetricsReport, WMetricsReport]("definitions.schema.json#/$defs/scopeReport")(toMetricsReport, fromMetricsReport, LegacyArtifacts.report)

object Summary:
  given JsonRW[Summary] = ArtifactCodec.mapped[Summary, WSummary]("definitions.schema.json#/$defs/summary")(toSummary, fromSummary)

object Cycle:
  given JsonRW[Cycle] = ArtifactCodec.mapped[Cycle, WCycle]("definitions.schema.json#/$defs/cycle")(toCycle, fromCycle)

object SurfaceRow:
  given JsonRW[SurfaceRow] = ArtifactCodec.mapped[SurfaceRow, WSurfaceRow]("definitions.schema.json#/$defs/surface")(toSurfaceRow, fromSurfaceRow, LegacyArtifacts.surface)

object PropagatorRow:
  given JsonRW[PropagatorRow] = ArtifactCodec.mapped[PropagatorRow, WPropagatorRow]("definitions.schema.json#/$defs/propagator")(toPropagatorRow, fromPropagatorRow)

object Finding:
  given JsonRW[Finding] = ArtifactCodec.mapped[Finding, WFinding]("definitions.schema.json#/$defs/finding")(toFinding, fromFinding)

object CutCandidate:
  given JsonRW[CutCandidate] = ArtifactCodec.mapped[CutCandidate, WCutCandidate]("definitions.schema.json#/$defs/cut")(toCutCandidate, fromCutCandidate)

object ReportTruncation:
  given JsonRW[ReportTruncation] = ArtifactCodec.mapped[ReportTruncation, (findingsOmitted: Long)]("definitions.schema.json#/$defs/scopeReport/properties/truncation")(
    v => (findingsOmitted = v.findingsOmitted.toLong), v => ReportTruncation(Math.toIntExact(v.findingsOmitted)))

object Solution:
  given JsonRW[Solution] = ArtifactCodec.mapped[Solution, (cuts: Seq[WCutCandidate])]("definitions.schema.json#/$defs/cutAnalysis/properties/solutions/items")(
    v => (cuts = v.cuts.map(toCutCandidate)), v => Solution(v.cuts.map(fromCutCandidate)))
