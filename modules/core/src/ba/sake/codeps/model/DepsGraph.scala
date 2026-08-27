package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/**
  * The standard JSON export format: a self-contained dependency graph with package/file/type/member
  * nodes and directed edges between node ids.
  * `{"nodes": [{"id": ..., "kind": ..., "parentId": ..., "file": ...}], "edges": [{"source": ..., "target": ...}]}`
  */
case class DepsGraph(nodes: Set[Node], edges: Set[Edge]) derives JsonRW:

  def merge(other: DepsGraph): DepsGraph =
    DepsGraph(nodes ++ other.nodes, edges ++ other.edges)

  /** Drops edges whose endpoints are not both in this graph's nodes. */
  def withoutDanglingEdges: DepsGraph =
    val ids = nodes.map(_.id)
    copy(edges = edges.filter(e => ids.contains(e.source) && ids.contains(e.target)))

object DepsGraph:
  val empty: DepsGraph = DepsGraph(Set.empty, Set.empty)
