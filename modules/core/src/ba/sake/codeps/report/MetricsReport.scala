package ba.sake.codeps.report

import ba.sake.tupson.JsonRW
import org.typelevel.jawn.ast.{JNull, JNum, JObject, JValue}

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
    orphans: Seq[String]
)

case class Summary(
    nodes: Int,
    edges: Int,
    nodesInCycles: Int,
    orphans: Int,
    criticalPathLength: Int
)

/** One cycle (multi-member SCC). `id` = "scc:" + smallest member id — stable
  * across recomputations after cuts, unlike a counter. `members` is a closed
  * cycle path through the smallest member (first node repeated at the end);
  * `size` is the full SCC member count, which may exceed the path length when
  * the SCC contains several interlocking cycles. */
case class Cycle(
    id: String,
    members: Seq[String],
    size: Int,
    extFanIn: Int,
    minCutsEstimate: Int,
    solutions: Seq[Solution],
    /** Display-only cycle density metadata. It is intentionally not serialized,
      * so the report JSON schema remains stable. */
    internalEdges: Int = 0
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
    utilization: Option[Double]
)

/** A node that propagates changes to an above-average part of the graph.
  * `score` = (fanIn/avgFanIn + fanOut/avgFanOut) / 2 — an exactly average node
  * scores 1.0; only nodes above 1.0 are listed, top 10 by score. */
case class PropagatorRow(node: String, fanIn: Int, fanOut: Int, score: Double)

object MetricsReport:
  given JsonRW[MetricsReport] with
    override def write(value: MetricsReport): JValue =
      obj(
        "scope" -> JsonRW[String].write(value.scope),
        "generatedAt" -> JsonRW[String].write(value.generatedAt),
        "summary" -> JsonRW[Summary].write(value.summary),
        "cycles" -> JsonRW[Seq[Cycle]].write(value.cycles),
        "propagators" -> JsonRW[Seq[PropagatorRow]].write(value.propagators),
        "surface" -> JsonRW[Seq[SurfaceRow]].write(value.surface),
        "orphans" -> JsonRW[Seq[String]].write(value.orphans)
      )
    override def parse(path: String, jValue: JValue): MetricsReport =
      throw new UnsupportedOperationException("metrics reports are write-only")

object Summary:
  given JsonRW[Summary] with
    override def write(value: Summary): JValue =
      obj(
        "nodes" -> JsonRW[Int].write(value.nodes),
        "edges" -> JsonRW[Int].write(value.edges),
        "nodesInCycles" -> JsonRW[Int].write(value.nodesInCycles),
        "orphans" -> JsonRW[Int].write(value.orphans),
        "criticalPathLength" -> JsonRW[Int].write(value.criticalPathLength)
      )
    override def parse(path: String, jValue: JValue): Summary =
      throw new UnsupportedOperationException("metrics reports are write-only")

object Cycle:
  given JsonRW[Cycle] with
    override def write(value: Cycle): JValue =
      obj(
        "id" -> JsonRW[String].write(value.id),
        "members" -> JsonRW[Seq[String]].write(value.members),
        "size" -> JsonRW[Int].write(value.size),
        "extFanIn" -> JsonRW[Int].write(value.extFanIn),
        "minCutsEstimate" -> JsonRW[Int].write(value.minCutsEstimate),
        "solutions" -> JsonRW[Seq[Solution]].write(value.solutions)
      )
    override def parse(path: String, jValue: JValue): Cycle =
      throw new UnsupportedOperationException("metrics reports are write-only")

object Solution:
  given JsonRW[Solution] with
    override def write(value: Solution): JValue =
      obj(
        "cuts" -> JsonRW[Seq[CutCandidate]].write(value.cuts)
      )
    override def parse(path: String, jValue: JValue): Solution =
      throw new UnsupportedOperationException("metrics reports are write-only")

object CutCandidate:
  given JsonRW[CutCandidate] with
    override def write(value: CutCandidate): JValue =
      obj(
        "edge" -> JsonRW[Seq[String]].write(Seq(value.source, value.target)),
        "weight" -> JsonRW[Int].write(value.weight)
      )
    override def parse(path: String, jValue: JValue): CutCandidate =
      throw new UnsupportedOperationException("metrics reports are write-only")

object SurfaceRow:
  given JsonRW[SurfaceRow] with
    override def write(value: SurfaceRow): JValue =
      obj(
        "node" -> JsonRW[String].write(value.node),
        "fanIn" -> JsonRW[Int].write(value.fanIn),
        "fanOut" -> JsonRW[Int].write(value.fanOut),
        "ports" -> num(value.ports),
        "mutPorts" -> num(value.mutPorts),
        "exposure" -> num(value.exposure),
        "utilization" -> (value.utilization match
          case None    => JNull
          case Some(d) => num(d))
      )
    override def parse(path: String, jValue: JValue): SurfaceRow =
      throw new UnsupportedOperationException("metrics reports are write-only")

object PropagatorRow:
  given JsonRW[PropagatorRow] with
    override def write(value: PropagatorRow): JValue =
      obj(
        "node" -> JsonRW[String].write(value.node),
        "fanIn" -> JsonRW[Int].write(value.fanIn),
        "fanOut" -> JsonRW[Int].write(value.fanOut),
        "score" -> num(value.score)
      )
    override def parse(path: String, jValue: JValue): PropagatorRow =
      throw new UnsupportedOperationException("metrics reports are write-only")

/** Integral doubles render as integers (9 not 9.0); fractional halves stay decimals. */
private def num(d: Double): JValue =
  if !d.isNaN && !d.isInfinite && d == math.rint(d) then JNum(d.toLong) else JNum(d)

private def obj(fields: (String, JValue)*): JValue =
  JObject(scala.collection.mutable.Map.from(fields))
