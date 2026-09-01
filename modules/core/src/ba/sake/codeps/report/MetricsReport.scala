package ba.sake.codeps.report

import ba.sake.tupson.JsonRW
import ba.sake.tupson.{ParseError, ParsingException, TupsonException}
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
    orphans: Seq[String],
    schemaVersion: Int = 2,
    findings: Seq[Finding] = Nil,
    /** Present only when the input exporter supplied complete public-symbol references. */
    publicSymbols: Option[Seq[PublicSymbolRow]] = None
):
  def publicSymbolUses: Option[Seq[PublicSymbolRow]] = publicSymbols
  def publicSymbolUse: Option[Seq[PublicSymbolRow]] = publicSymbols
  def symbolUses: Option[Seq[PublicSymbolRow]] = publicSymbols

case class Summary(
    nodes: Int,
    edges: Int,
    nodesInCycles: Int,
    orphans: Int,
    criticalPathLength: Int
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

/** Public declaration use derived from optional SemanticDB reference records.
  * `referenceCount` counts occurrences; `consumerCount` counts distinct source
  * files. `usageConfidence` is `semanticdbComplete` when this index is present.
  */
case class PublicSymbolRow(
    symbol: String,
    consumerCount: Int,
    referenceCount: Int,
    usageConfidence: String
):
  def targetSymbol: String = symbol

/** A node that propagates changes to an above-average part of the graph.
  * `score` = (fanIn/avgFanIn + fanOut/avgFanOut) / 2 — an exactly average node
  * scores 1.0; only nodes above 1.0 are listed. The table presentation applies
  * its own top-10 bound; JSON retains the complete index. */
case class PropagatorRow(node: String, fanIn: Int, fanOut: Int, score: Double)

object PublicSymbolRow:
  given JsonRW[PublicSymbolRow] with
    override def write(value: PublicSymbolRow): JValue =
      obj(
        "symbol" -> JsonRW[String].write(value.symbol),
        "consumerCount" -> JsonRW[Int].write(value.consumerCount),
        "referenceCount" -> JsonRW[Int].write(value.referenceCount),
        "usageConfidence" -> JsonRW[String].write(value.usageConfidence)
      )
    override def parse(path: String, jValue: JValue): PublicSymbolRow =
      val map = objectFields(path, jValue)
      PublicSymbolRow(
        requiredString(map, path, "symbol"),
        required[Int](map, path, "consumerCount"),
        required[Int](map, path, "referenceCount"),
        requiredString(map, path, "usageConfidence")
      )

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
  given JsonRW[MetricsReport] with
    override def write(value: MetricsReport): JValue =
      val fields = scala.collection.mutable.Map[String, JValue](
        // Schema version is a wire-level contract; callers cannot emit a different version.
        "schemaVersion" -> JsonRW[Int].write(2),
        "scope" -> JsonRW[String].write(value.scope),
        "generatedAt" -> JsonRW[String].write(value.generatedAt),
        "summary" -> JsonRW[Summary].write(value.summary),
        "cycles" -> JsonRW[Seq[Cycle]].write(value.cycles),
        "propagators" -> JsonRW[Seq[PropagatorRow]].write(value.propagators),
        "surface" -> JsonRW[Seq[SurfaceRow]].write(value.surface),
        "orphans" -> JsonRW[Seq[String]].write(value.orphans),
        "findings" -> JsonRW[Seq[Finding]].write(value.findings)
      )
      value.publicSymbols.foreach(symbols => fields("publicSymbols") = JsonRW[Seq[PublicSymbolRow]].write(symbols))
      JObject(fields)
    override def parse(path: String, jValue: JValue): MetricsReport =
      val map = objectFields(path, jValue)
      val schemaVersion = required[Int](map, path, "schemaVersion")
      if schemaVersion != 2 then
        throw TupsonException(s"incompatible schema version: $schemaVersion (expected 2)")
      MetricsReport(
        requiredString(map, path, "scope"),
        requiredString(map, path, "generatedAt"),
        required[Summary](map, path, "summary"),
        required[Seq[Cycle]](map, path, "cycles"),
        required[Seq[PropagatorRow]](map, path, "propagators"),
        required[Seq[SurfaceRow]](map, path, "surface"),
        required[Seq[String]](map, path, "orphans"),
        schemaVersion,
        required[Seq[Finding]](map, path, "findings"),
        map.get("publicSymbols") match
          case None    => None
          case Some(v) => Some(JsonRW[Seq[PublicSymbolRow]].parse(s"$path.publicSymbols", v))
      )

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
      val map = objectFields(path, jValue)
      Summary(
        required[Int](map, path, "nodes"),
        required[Int](map, path, "edges"),
        required[Int](map, path, "nodesInCycles"),
        required[Int](map, path, "orphans"),
        required[Int](map, path, "criticalPathLength")
      )

object Cycle:
  given JsonRW[Cycle] with
    override def write(value: Cycle): JValue =
      obj(
        "id" -> JsonRW[String].write(value.id),
        "members" -> JsonRW[Seq[String]].write(value.members),
        "size" -> JsonRW[Int].write(value.size),
        "extFanIn" -> JsonRW[Int].write(value.extFanIn),
        "internalEdges" -> JsonRW[Int].write(value.internalEdges),
        "incomingEdges" -> JsonRW[Int].write(value.incomingEdges),
        "outgoingEdges" -> JsonRW[Int].write(value.outgoingEdges),
        "cutAnalysis" -> JsonRW[CutAnalysis].write(value.cutAnalysis),
        "witnessCycle" -> JsonRW[Seq[String]].write(value.witnessCycle)
      )
    override def parse(path: String, jValue: JValue): Cycle =
      val map = objectFields(path, jValue)
      Cycle(
        requiredString(map, path, "id"),
        required[Seq[String]](map, path, "members"),
        required[Int](map, path, "size"),
        required[Int](map, path, "extFanIn"),
        required[CutAnalysis](map, path, "cutAnalysis"),
        required[Int](map, path, "internalEdges"),
        required[Seq[String]](map, path, "witnessCycle"),
        required[Int](map, path, "incomingEdges"),
        required[Int](map, path, "outgoingEdges")
      )

object Solution:
  given JsonRW[Solution] with
    override def write(value: Solution): JValue =
      obj(
        "cuts" -> JsonRW[Seq[CutCandidate]].write(value.cuts)
      )
    override def parse(path: String, jValue: JValue): Solution =
      val map = objectFields(path, jValue)
      Solution(required[Seq[CutCandidate]](map, path, "cuts"))

object CutCandidate:
  given JsonRW[CutCandidate] with
    override def write(value: CutCandidate): JValue =
      obj(
        "edge" -> JsonRW[Seq[String]].write(Seq(value.source, value.target)),
        "weight" -> JsonRW[Int].write(value.weight)
      )
    override def parse(path: String, jValue: JValue): CutCandidate =
      val map = objectFields(path, jValue)
      val edge = required[Seq[String]](map, path, "edge")
      if edge.size != 2 then
        throw ParsingException(ParseError(s"$path.edge", "must contain exactly two node ids", Some(edge)))
      CutCandidate(edge.head, edge(1), required[Int](map, path, "weight"))

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
        "dependentsPerPublicPort" -> (value.dependentsPerPublicPort match
          case None    => JNull
          case Some(d) => num(d)),
        "cycleId" -> (value.cycleId match
          case None     => JNull
          case Some(id) => JsonRW[String].write(id)),
        "publicSurface" -> num(value.publicSurface),
        "protectedSurface" -> num(value.protectedSurface),
        "packageSurface" -> num(value.packageSurface),
        "privateSurface" -> num(value.privateSurface),
        "publicMutableSurface" -> num(value.publicMutableSurface),
        "protectedMutableSurface" -> num(value.protectedMutableSurface),
        "packageMutableSurface" -> num(value.packageMutableSurface),
        "privateMutableSurface" -> num(value.privateMutableSurface),
        "totalDeclaredSurface" -> num(value.totalDeclaredSurface),
        "encapsulationRatio" -> optionalNum(value.encapsulationRatio),
        "publicMutableRatio" -> optionalNum(value.publicMutableRatio)
      )
    override def parse(path: String, jValue: JValue): SurfaceRow =
      val map = objectFields(path, jValue)
      SurfaceRow(
        requiredString(map, path, "node"),
        required[Int](map, path, "fanIn"),
        required[Int](map, path, "fanOut"),
        required[Double](map, path, "ports"),
        required[Double](map, path, "mutPorts"),
        required[Double](map, path, "exposure"),
        proxy(map, path),
        required[Option[String]](map, path, "cycleId"),
        optionalDouble(map, path, "publicSurface"),
        optionalDouble(map, path, "protectedSurface"),
        optionalDouble(map, path, "packageSurface"),
        optionalDouble(map, path, "privateSurface"),
        optionalDouble(map, path, "publicMutableSurface"),
        optionalDouble(map, path, "protectedMutableSurface"),
        optionalDouble(map, path, "packageMutableSurface"),
        optionalDouble(map, path, "privateMutableSurface"),
        optionalDouble(map, path, "totalDeclaredSurface"),
        optionalOptionDouble(map, path, "encapsulationRatio"),
        optionalOptionDouble(map, path, "publicMutableRatio")
      )

    private def proxy(map: scala.collection.mutable.Map[String, JValue], path: String): Option[Double] =
      map.get("dependentsPerPublicPort").orElse(map.get("utilization")) match
        case None    => None
        case Some(v) => JsonRW[Option[Double]].parse(s"$path.dependentsPerPublicPort", v)

    private def optionalDouble(map: scala.collection.mutable.Map[String, JValue], path: String, key: String): Double =
      map.get(key) match
        case None    => 0.0
        case Some(v) => JsonRW[Double].parse(s"$path.$key", v)

    private def optionalOptionDouble(map: scala.collection.mutable.Map[String, JValue], path: String, key: String): Option[Double] =
      map.get(key) match
        case None    => None
        case Some(v) => JsonRW[Option[Double]].parse(s"$path.$key", v)

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
      val map = objectFields(path, jValue)
      PropagatorRow(
        requiredString(map, path, "node"),
        required[Int](map, path, "fanIn"),
        required[Int](map, path, "fanOut"),
        required[Double](map, path, "score")
      )

object Finding:
  given JsonRW[Finding] with
    override def write(value: Finding): JValue =
      obj(
        "id" -> JsonRW[String].write(value.id),
        "kind" -> JsonRW[String].write(value.kind),
        "severity" -> JsonRW[String].write(value.severity),
        "subject" -> JsonRW[String].write(value.subject),
        "evidence" -> JsonRW[String].write(value.evidence),
        "confidence" -> JsonRW[String].write(value.confidence),
        "nextAction" -> JsonRW[String].write(value.nextAction)
      )
    override def parse(path: String, jValue: JValue): Finding =
      val map = objectFields(path, jValue)
      Finding(
        requiredString(map, path, "id"),
        requiredString(map, path, "kind"),
        requiredString(map, path, "severity"),
        requiredString(map, path, "subject"),
        requiredString(map, path, "evidence"),
        requiredString(map, path, "confidence"),
        requiredString(map, path, "nextAction")
      )

/** Integral doubles render as integers (9 not 9.0); fractional halves stay decimals. */
private def num(d: Double): JValue =
  if !d.isNaN && !d.isInfinite && d == math.rint(d) then JNum(d.toLong) else JNum(d)

private def optionalNum(value: Option[Double]): JValue = value match
  case None    => JNull
  case Some(d) => num(d)

private def obj(fields: (String, JValue)*): JValue =
  JObject(scala.collection.mutable.Map.from(fields))

private def objectFields(path: String, jValue: JValue): scala.collection.mutable.Map[String, JValue] =
  jValue match
    case JObject(fields) => fields
    case other =>
      throw ParsingException(
        ParseError(path, s"should be Object but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
      )

private def required[T](fields: scala.collection.mutable.Map[String, JValue], path: String, key: String)(using rw: JsonRW[T]): T =
  fields.get(key) match
    case Some(value) => rw.parse(s"$path.$key", value)
    case None        => throw ParsingException(ParseError(s"$path.$key", "is missing"))

private def requiredString(fields: scala.collection.mutable.Map[String, JValue], path: String, key: String): String =
  required[String](fields, path, key)
