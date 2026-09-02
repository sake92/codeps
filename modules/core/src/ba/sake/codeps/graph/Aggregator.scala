package ba.sake.codeps.graph

import ba.sake.codeps.model.*

/** Collapses a granular graph (package/file/type/member) into the internal
  * package-and-file projection. Each node's `ports`/`mutPorts` contribution
  * lands in exactly one surviving node, and edges are re-wired with summed weights.
  * Type/member nodes collapse into their `file` node; file-less nodes (jdeps
  * types) collapse into their root package. File nodes get `parentId` = their
  * root package (lexicographically smallest when a file hosts several packages)
  * so `report --scope packages` aggregation keeps working. */
object Aggregator:

  /** Builds the public export from one collapsed projection. The two scopes are
    * intentionally derived together so their node summaries and edge weights do
    * not drift apart. */
  def toExport(graph: DepsGraph): ExportGraph =
    val combined = fileLevel(graph)
    val files = combined.nodes.collect {
      case n if n.kind == NodeKind.file =>
        FileNode(n.id, n.parentId, n.ports, n.mutPorts, n.declarationSurface)
    }
    val packagesById = combined.nodes.collect {
      case n if n.kind == NodeKind.`package` => n.id -> n
    }.toMap
    val fileSummariesByPackage = files.toSeq.flatMap { file =>
      file.packageId.map(_ -> file)
    }.groupMap(_._1)(_._2)
    val packageIds = packagesById.keySet ++ fileSummariesByPackage.keySet
    val packages = packageIds.map { id =>
      val direct = packagesById.getOrElse(id, Node(id, NodeKind.`package`))
      val fileSummaries = fileSummariesByPackage.getOrElse(id, Nil)
      PackageNode(
        id,
        direct.ports + fileSummaries.map(_.ports).sum,
        direct.mutPorts + fileSummaries.map(_.mutPorts).sum,
        fileSummaries.foldLeft(direct.declarationSurface)(_ + _.declarationSurface)
      )
    }

    val packageIdByNodeId =
      packagesById.keys.map(id => id -> id).toMap ++ files.flatMap(f => f.packageId.map(f.id -> _))
    val packageEdges = aggregateEdges(combined.edges) { id => packageIdByNodeId.get(id) }
    val fileIds = files.map(_.id)
    val fileEdges = combined.edges.filter(e => fileIds.contains(e.source) && fileIds.contains(e.target))
    ExportGraph(PackageGraph(packages, packageEdges), FileGraph(files, fileEdges))

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
    val declarationSurface = graph.nodes.toSeq
      .flatMap(n => aggId(n).map(id => id -> n.declarationSurface))
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
          mutPorts = mutPorts.getOrElse(f.id, 0.0),
          declarationSurface = declarationSurface.getOrElse(f.id, DeclarationSurface())
        )
    }
    val pkgNodes = graph.nodes.collect {
      case p if p.kind == NodeKind.`package` =>
        p.copy(
          ports = ports.getOrElse(p.id, 0.0),
          mutPorts = mutPorts.getOrElse(p.id, 0.0),
          declarationSurface = declarationSurface.getOrElse(p.id, DeclarationSurface())
        )
    }
    DepsGraph(fileNodes ++ pkgNodes, edges)

  private def aggregateEdges(edges: Set[Edge])(id: String => Option[String]): Set[Edge] =
    edges.toSeq
      .flatMap { edge =>
        for
          source <- id(edge.source)
          target <- id(edge.target)
          if source != target
        yield ((source, target), edge.weight)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .map { case ((source, target), weight) => Edge(source, target, weight) }
      .toSet
