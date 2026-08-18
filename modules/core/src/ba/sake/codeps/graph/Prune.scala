package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Prune:

  /** Drops `package` nodes that are the root package of no remaining node —
    * e.g. after filtering removed all their children. Idempotent. */
  def emptyPackages(graph: DepsGraph): DepsGraph =
    val nodesById = graph.nodes.map(n => n.id -> n).toMap
    val alive = graph.nodes.filter(_.kind != NodeKind.`package`).flatMap(_.rootPackageId(nodesById))
    graph.copy(nodes = graph.nodes.filter(n => n.kind != NodeKind.`package` || alive.contains(n.id)))
