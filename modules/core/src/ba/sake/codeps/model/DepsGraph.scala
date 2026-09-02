package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** Internal dependency graph shared by input parsers and report calculations. */
case class DepsGraph(
    nodes: Set[Node],
    edges: Set[Edge]
) derives JsonRW:

  def merge(other: DepsGraph): DepsGraph =
    if nodes.isEmpty && edges.isEmpty then other
    else if other.nodes.isEmpty && other.edges.isEmpty then this
    else DepsGraph(nodes ++ other.nodes, edges ++ other.edges)

  /** Drops edges whose endpoints are not both in this graph's nodes. */
  def withoutDanglingEdges: DepsGraph =
    val ids = nodes.map(_.id)
    copy(edges = edges.filter(e => ids.contains(e.source) && ids.contains(e.target)))

object DepsGraph:
  val empty: DepsGraph = DepsGraph(Set.empty, Set.empty)
