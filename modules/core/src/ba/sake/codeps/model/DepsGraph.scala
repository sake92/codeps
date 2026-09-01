package ba.sake.codeps.model

import ba.sake.tupson.{JsonRW, ParseError, ParsingException}
import org.typelevel.jawn.ast.{JObject, JValue}

/**
  * The standard JSON export format: a self-contained dependency graph with package/file/type/member
  * nodes and directed edges between node ids. Optional SemanticDB metadata
  * carries exact symbol-reference occurrences after granular nodes are collapsed.
  * `{"nodes": [{"id": ..., "kind": ..., "parentId": ..., "file": ...}], "edges": [{"source": ..., "target": ...}]}`
  */
case class DepsGraph(
    nodes: Set[Node],
    edges: Set[Edge],
    /** Optional exact public-symbol references. `None` means the exporter did
      * not provide a complete reference index (for example jdeps). */
    symbolReferences: Option[Seq[SymbolReference]] = None,
    /** Optional declaration ids retained when a producer aggregates away its
      * granular type/member nodes. Values are the source-file ids used by the
      * reference records. */
    declaredPublicSymbols: Option[Map[String, String]] = None
):

  def merge(other: DepsGraph): DepsGraph =
    if nodes.isEmpty && edges.isEmpty then other
    else if other.nodes.isEmpty && other.edges.isEmpty then this
    else
      DepsGraph(nodes ++ other.nodes, edges ++ other.edges, mergeReferences(other), mergeDeclarations(other))

  /** Drops edges whose endpoints are not both in this graph's nodes. */
  def withoutDanglingEdges: DepsGraph =
    val ids = nodes.map(_.id)
    val nodePublicSymbols = nodes
      .filter(n => (n.kind == NodeKind.`type` || n.kind == NodeKind.member) && n.isExposed)
      .map(_.id)
    val publicSymbols = nodePublicSymbols ++ declaredPublicSymbols.toSeq.flatMap(_.keys)
    copy(
      edges = edges.filter(e => ids.contains(e.source) && ids.contains(e.target)),
      symbolReferences = symbolReferences.map(_.filter(r => publicSymbols.contains(r.targetSymbol))),
      declaredPublicSymbols = declaredPublicSymbols.map(_.filter((symbol, _) => publicSymbols.contains(symbol)))
    )

  private def mergeReferences(other: DepsGraph): Option[Seq[SymbolReference]] =
    (symbolReferences, other.symbolReferences) match
      case (Some(left), Some(right)) => Some(left ++ right)
      // Once two non-empty graphs are combined, an absent index means the
      // aggregate cannot claim complete SemanticDB use evidence.
      case _ => None

  private def mergeDeclarations(other: DepsGraph): Option[Map[String, String]] =
    (declaredPublicSymbols, other.declaredPublicSymbols) match
      case (Some(left), Some(right)) => Some(left ++ right)
      case _ => None

object DepsGraph:
  val empty: DepsGraph = DepsGraph(Set.empty, Set.empty)

  given JsonRW[DepsGraph] with
    override def write(value: DepsGraph): JValue =
      val fields = scala.collection.mutable.Map[String, JValue](
        "nodes" -> JsonRW[Set[Node]].write(value.nodes),
        "edges" -> JsonRW[Set[Edge]].write(value.edges)
      )
      value.symbolReferences.foreach(refs => fields("symbolReferences") = JsonRW[Seq[SymbolReference]].write(refs))
      value.declaredPublicSymbols.foreach(symbols => fields("declaredPublicSymbols") = JsonRW[Map[String, String]].write(symbols))
      JObject(fields)

    override def parse(path: String, jValue: JValue): DepsGraph = jValue match
      case JObject(map) =>
        val nodes = required[Set[Node]](map, path, "nodes")
        val edges = required[Set[Edge]](map, path, "edges")
        val refs = map.get("symbolReferences") match
          case None    => None
          case Some(v) => Some(JsonRW[Seq[SymbolReference]].parse(s"$path.symbolReferences", v))
        val symbols = map.get("declaredPublicSymbols") match
          case None    => None
          case Some(v) => Some(JsonRW[Map[String, String]].parse(s"$path.declaredPublicSymbols", v))
        DepsGraph(nodes, edges, refs, symbols)
      case other =>
        throw ParsingException(
          ParseError(path, s"should be Object but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
        )

    private def required[T](map: scala.collection.mutable.Map[String, JValue], path: String, key: String)(using rw: JsonRW[T]): T =
      map.get(key) match
        case Some(value) => rw.parse(s"$path.$key", value)
        case None        => throw ParsingException(ParseError(s"$path.$key", "is missing"))
