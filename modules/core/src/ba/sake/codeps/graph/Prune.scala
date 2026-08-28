package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Prune:

  /** Drops `package` nodes that are the root package of no remaining node —
    * e.g. after filtering removed all their children. Idempotent.
    * Package-only graphs (jdeps export, go list, pydeps) have no non-package
    * nodes: every package there IS the content, so nothing is pruned. */
  def emptyPackages(graph: DepsGraph): DepsGraph =
    val nonPackage = graph.nodes.filter(_.kind != NodeKind.`package`)
    if nonPackage.isEmpty then graph
    else
      val nodesById = graph.nodes.map(n => n.id -> n).toMap
      val alive = nonPackage.flatMap(_.rootPackageId(nodesById))
      graph.copy(nodes = graph.nodes.filter(n => n.kind != NodeKind.`package` || alive.contains(n.id)))
