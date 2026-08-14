package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Filter:
  /**
    * Universe = nodes whose root package matches an include pattern, minus nodes whose
    * root package matches an exclude pattern (exclude wins). A pattern `ba.sake` matches
    * `ba.sake` itself and everything below it. With no includes, all nodes are kept.
    * Edges are kept only when both endpoints are in the universe; self-edges dropped.
    */
  def apply(graph: DepsGraph, includes: Seq[String], excludes: Seq[String]): DepsGraph =
    val nodesById = graph.nodes.map(n => n.id -> n).toMap
    val universe = graph.nodes
      .filter(n => includes.isEmpty || includes.exists(pat => n.rootPackageId(nodesById).exists(matches(_, pat))))
      .filterNot(n => excludes.exists(pat => n.rootPackageId(nodesById).exists(matches(_, pat))))
    val keptIds = universe.map(_.id)
    val keptEdges = graph.edges.filter(e => keptIds.contains(e.source) && keptIds.contains(e.target) && e.source != e.target)
    DepsGraph(universe, keptEdges)

  private def matches(pkg: String, pattern: String): Boolean =
    pkg == pattern || pkg.startsWith(pattern + ".")
