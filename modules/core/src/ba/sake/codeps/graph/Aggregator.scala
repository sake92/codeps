package ba.sake.codeps.graph

import ba.sake.codeps.model.*

/** Maps a DepsGraph to a coarser granularity level. */
object Aggregator:

  /** The level the analyzer works at. */
  enum Level:
    case Package, File, Type, Member

  /**
    * Maps every node to its nearest ancestor at `level` (or itself if it is at that level),
    * falling back to the nearest coarser ancestor when the requested level is absent
    * (e.g. jdeps data has no file/member nodes). Nodes coarser than the requested level are
    * dropped: file nodes at `type`/`package` levels, package nodes at `type`/`file` levels
    * (unless a finer node falls back to them: package-parented members at `type` level,
    * file-less types at `file` level). Edges are lifted through the same mapping;
    * self-loops and edges with dropped endpoints are removed.
    */
  def aggregate(graph: DepsGraph, level: Level): (Set[String], Set[Edge]) =
    val nodesById = graph.nodes.map(n => n.id -> n).toMap
    def mapNode(n: Node): Option[String] = level match
      case Level.Member => Some(n.id)
      case Level.Type =>
        n.kind match
          case NodeKind.`package` => None
          case NodeKind.`type`    => Some(n.id)
          case NodeKind.member    => n.parentId
          case NodeKind.file      => None
      case Level.File =>
        n.kind match
          case NodeKind.`package` => None
          case NodeKind.file      => Some(n.id)
          case _                  => n.file.orElse(n.rootPackageId(nodesById))
      case Level.Package =>
        n.kind match
          case NodeKind.`package` => Some(n.id)
          case NodeKind.file      => None
          case _                  => n.rootPackageId(nodesById)
    val ids = graph.nodes.flatMap(mapNode)
    val edges = graph.edges.flatMap { e =>
      for
        s <- nodesById.get(e.source).flatMap(mapNode)
        t <- nodesById.get(e.target).flatMap(mapNode)
        if s != t
      yield Edge(s, t)
    }
    (ids, edges)
