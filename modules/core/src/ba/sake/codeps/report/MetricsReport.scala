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
    cutCandidates: Seq[CutCandidate]
)

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

object MetricsReport:
  given JsonRW[MetricsReport] with
    override def write(value: MetricsReport): JValue =
      obj(
        "scope" -> JsonRW[String].write(value.scope),
        "generatedAt" -> JsonRW[String].write(value.generatedAt),
        "summary" -> JsonRW[Summary].write(value.summary),
        "cycles" -> JsonRW[Seq[Cycle]].write(value.cycles),
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
        "cutCandidates" -> JsonRW[Seq[CutCandidate]].write(value.cutCandidates)
      )
    override def parse(path: String, jValue: JValue): Cycle =
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

/** Integral doubles render as integers (9 not 9.0); fractional halves stay decimals. */
private def num(d: Double): JValue =
  if !d.isNaN && !d.isInfinite && d == math.rint(d) then JNum(d.toLong) else JNum(d)

private def obj(fields: (String, JValue)*): JValue =
  JObject(scala.collection.mutable.Map.from(fields))
