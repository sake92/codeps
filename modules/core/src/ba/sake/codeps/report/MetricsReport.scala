package ba.sake.codeps.report

import ba.sake.tupson.JsonRW
import org.typelevel.jawn.ast.{JNull, JNum, JObject, JValue}

/** The flat metrics report — codeps's only user-facing output (v2.md §5).
  * Field names are snake_case; every value is derived fresh from the scope's
  * node/edge list on each run, never cached. */
case class MetricsReport(
    scope: String, // "packages" | "files"
    generatedAt: String, // ISO8601
    summary: Summary,
    knots: Seq[Knot],
    surface: Seq[SurfaceRow],
    orphans: Seq[String],
    articulationPoints: Seq[String]
)

case class Summary(
    nodes: Int,
    edges: Int,
    nodesInCycles: Int,
    orphans: Int,
    criticalPathLength: Int
)

/** One knot (multi-member SCC). `id` = "scc:" + smallest member id — stable
  * across recomputations after cuts, unlike a counter. */
case class Knot(
    id: String,
    members: Seq[String],
    size: Int,
    extFanIn: Int,
    minCutsEstimate: Int,
    cutCandidates: Seq[CutCandidate]
)

/** A simulated edge cut. `effect` is "resolved" | "partial" | "none";
  * `newSize` is the resulting component size (1 when resolved). */
case class CutCandidate(source: String, target: String, weight: Int, effect: String, newSize: Int)

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
        "generated_at" -> JsonRW[String].write(value.generatedAt),
        "summary" -> JsonRW[Summary].write(value.summary),
        "knots" -> JsonRW[Seq[Knot]].write(value.knots),
        "surface" -> JsonRW[Seq[SurfaceRow]].write(value.surface),
        "orphans" -> JsonRW[Seq[String]].write(value.orphans),
        "articulation_points" -> JsonRW[Seq[String]].write(value.articulationPoints)
      )
    override def parse(path: String, jValue: JValue): MetricsReport =
      throw new UnsupportedOperationException("metrics reports are write-only")

object Summary:
  given JsonRW[Summary] with
    override def write(value: Summary): JValue =
      obj(
        "nodes" -> JsonRW[Int].write(value.nodes),
        "edges" -> JsonRW[Int].write(value.edges),
        "nodes_in_cycles" -> JsonRW[Int].write(value.nodesInCycles),
        "orphans" -> JsonRW[Int].write(value.orphans),
        "critical_path_length" -> JsonRW[Int].write(value.criticalPathLength)
      )
    override def parse(path: String, jValue: JValue): Summary =
      throw new UnsupportedOperationException("metrics reports are write-only")

object Knot:
  given JsonRW[Knot] with
    override def write(value: Knot): JValue =
      obj(
        "id" -> JsonRW[String].write(value.id),
        "members" -> JsonRW[Seq[String]].write(value.members),
        "size" -> JsonRW[Int].write(value.size),
        "ext_fan_in" -> JsonRW[Int].write(value.extFanIn),
        "min_cuts_estimate" -> JsonRW[Int].write(value.minCutsEstimate),
        "cut_candidates" -> JsonRW[Seq[CutCandidate]].write(value.cutCandidates)
      )
    override def parse(path: String, jValue: JValue): Knot =
      throw new UnsupportedOperationException("metrics reports are write-only")

object CutCandidate:
  given JsonRW[CutCandidate] with
    override def write(value: CutCandidate): JValue =
      obj(
        "edge" -> JsonRW[Seq[String]].write(Seq(value.source, value.target)),
        "weight" -> JsonRW[Int].write(value.weight),
        "effect" -> JsonRW[String].write(value.effect),
        "new_size" -> JsonRW[Int].write(value.newSize)
      )
    override def parse(path: String, jValue: JValue): CutCandidate =
      throw new UnsupportedOperationException("metrics reports are write-only")

object SurfaceRow:
  given JsonRW[SurfaceRow] with
    override def write(value: SurfaceRow): JValue =
      obj(
        "node" -> JsonRW[String].write(value.node),
        "fan_in" -> JsonRW[Int].write(value.fanIn),
        "fan_out" -> JsonRW[Int].write(value.fanOut),
        "ports" -> num(value.ports),
        "mut_ports" -> num(value.mutPorts),
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
