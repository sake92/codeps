package ba.sake.codeps.graph

import ba.sake.codeps.model.*

/** Collapses a granular graph (package/file/type/member) into the exported
  * shape — package and file nodes only (codeps operates at exactly these two
  * granularities). Each node's `ports`/`mutPorts` contribution lands in exactly
  * one surviving node, and edges are re-wired with summed weights.
  * Type/member nodes collapse into their `file` node; file-less nodes (jdeps
  * types) collapse into their root package. File nodes get `parentId` = their
  * root package (lexicographically smallest when a file hosts several packages)
  * so `report --scope packages` aggregation keeps working. */
object Aggregator:

  def fileLevel(graph: DepsGraph): DepsGraph =
    val nodesById = graph.nodes.map(n => n.id -> n).toMap

    /** Surviving id of every node: file/package nodes keep their id; type/member
      * nodes collapse into their file, or their root package when file-less. */
    def aggId(n: Node): Option[String] = n.kind match
      case NodeKind.`package` | NodeKind.file => Some(n.id)
      case _                                   => n.file.orElse(n.rootPackageId(nodesById))

    val ports = graph.nodes.toSeq
      .flatMap(n => aggId(n).map(id => id -> n.ports))
      .groupMapReduce(_._1)(_._2)(_ + _)
    val mutPorts = graph.nodes.toSeq
      .flatMap(n => aggId(n).map(id => id -> n.mutPorts))
      .groupMapReduce(_._1)(_._2)(_ + _)

    val filePackages = graph.nodes.toSeq
      .flatMap { n =>
        if n.kind == NodeKind.`package` || n.kind == NodeKind.file then Nil
        else n.file.toSeq.flatMap(f => n.rootPackageId(nodesById).toSeq.map(pkg => f -> pkg))
      }
      .groupMap(_._1)(_._2)
      .view.mapValues(_.min)
      .toMap

    val edges = graph.edges.toSeq
      .flatMap { e =>
        for
          s <- nodesById.get(e.source).flatMap(aggId)
          t <- nodesById.get(e.target).flatMap(aggId)
          if s != t
        yield ((s, t), e.weight)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .map { case ((s, t), w) => Edge(s, t, w) }
      .toSet

    val fileNodes = graph.nodes.collect {
      case f if f.kind == NodeKind.file =>
        f.copy(
          parentId = filePackages.get(f.id),
          ports = ports.getOrElse(f.id, 0.0),
          mutPorts = mutPorts.getOrElse(f.id, 0.0)
        )
    }
    val pkgNodes = graph.nodes.collect {
      case p if p.kind == NodeKind.`package` =>
        p.copy(ports = ports.getOrElse(p.id, 0.0), mutPorts = mutPorts.getOrElse(p.id, 0.0))
    }
    DepsGraph(fileNodes ++ pkgNodes, edges)
