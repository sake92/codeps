package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** Public, scope-specific representation of a package summary. */
case class PackageNode(
    id: String,
    ports: Double,
    mutPorts: Double,
    declarationSurface: DeclarationSurface
) derives JsonRW

/** Public, scope-specific representation of a source-file summary. */
case class FileNode(
    id: String,
    packageId: Option[String],
    ports: Double,
    mutPorts: Double,
    declarationSurface: DeclarationSurface
) derives JsonRW

case class PackageGraph(nodes: Set[PackageNode], edges: Set[Edge]) derives JsonRW:
  private[codeps] def toDepsGraph: DepsGraph =
    DepsGraph(
      nodes.map(n => Node(n.id, NodeKind.`package`, ports = n.ports, mutPorts = n.mutPorts,
        declarationSurface = n.declarationSurface)),
      edges
    )

case class FileGraph(nodes: Set[FileNode], edges: Set[Edge]) derives JsonRW:
  private[codeps] def toDepsGraph: DepsGraph =
    val files = nodes.map(n => Node(n.id, NodeKind.file, n.packageId, ports = n.ports, mutPorts = n.mutPorts,
      declarationSurface = n.declarationSurface))
    val packages = nodes.flatMap(_.packageId).map(id => Node(id, NodeKind.`package`))
    DepsGraph(files ++ packages, edges)

/**
  * The codeps export format. Package and file graphs are separate materialized
  * views, so consumers never need to infer a scope from implementation-detail
  * node kinds or symbol ids.
  */
case class ExportGraph(packages: PackageGraph, files: FileGraph) derives JsonRW:
  def packageDeps: DepsGraph = packages.toDepsGraph
  def fileDeps: DepsGraph = files.toDepsGraph
