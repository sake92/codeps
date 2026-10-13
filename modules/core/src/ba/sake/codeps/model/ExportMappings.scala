package ba.sake.codeps.model

import ba.sake.codeps.artifacts.models

/** Generated export types are mapped to the graph representations used by the
  * parsers.
  */
private[codeps] object ExportMappings {
  type WDeclarationSurface =
    models.Artifact__declarationSurface
  type WEdge = models.Artifact__edge
  type WPackageNode = models.Artifact__packageNode
  type WFileNode = models.Artifact__fileNode
  type WExportGraph = models.ExportGraph

  def toDeclarationSurface(v: DeclarationSurface): WDeclarationSurface =
    models.Artifact__declarationSurface(
      public = Some(v.public.toLong),
      `protected` = Some(v.`protected`.toLong),
      packageRestricted = Some(v.packageRestricted.toLong),
      privateMembers = Some(v.privateMembers.toLong),
      publicMutable = Some(v.publicMutable.toLong),
      protectedMutable = Some(v.protectedMutable.toLong),
      packageRestrictedMutable = Some(v.packageRestrictedMutable.toLong),
      privateMutable = Some(v.privateMutable.toLong)
    )
  def fromDeclarationSurface(v: WDeclarationSurface): DeclarationSurface =
    DeclarationSurface(
      public = Math.toIntExact(v.public.getOrElse(0L)),
      `protected` = Math.toIntExact(v.`protected`.getOrElse(0L)),
      packageRestricted = Math.toIntExact(v.packageRestricted.getOrElse(0L)),
      privateMembers = Math.toIntExact(v.privateMembers.getOrElse(0L)),
      publicMutable = Math.toIntExact(v.publicMutable.getOrElse(0L)),
      protectedMutable = Math.toIntExact(v.protectedMutable.getOrElse(0L)),
      packageRestrictedMutable =
        Math.toIntExact(v.packageRestrictedMutable.getOrElse(0L)),
      privateMutable = Math.toIntExact(v.privateMutable.getOrElse(0L))
    )

  def toEdge(v: Edge): WEdge = models.Artifact__edge(
    source = v.source,
    target = v.target,
    weight = Some(v.weight.toLong)
  )
  def fromEdge(v: WEdge): Edge = Edge(
    source = v.source,
    target = v.target,
    weight = Math.toIntExact(v.weight.getOrElse(1L))
  )

  def toPackageNode(v: PackageNode): WPackageNode =
    models.Artifact__packageNode(
      id = v.id,
      ports = v.ports,
      mutPorts = v.mutPorts,
      declarationSurface = toDeclarationSurface(v.declarationSurface)
    )
  def fromPackageNode(v: WPackageNode): PackageNode = PackageNode(
    id = v.id,
    ports = v.ports,
    mutPorts = v.mutPorts,
    declarationSurface = fromDeclarationSurface(v.declarationSurface)
  )

  def toFileNode(v: FileNode): WFileNode =
    models.Artifact__fileNode(
      id = v.id,
      packageId = v.packageId,
      ports = v.ports,
      mutPorts = v.mutPorts,
      declarationSurface = toDeclarationSurface(v.declarationSurface)
    )
  def fromFileNode(v: WFileNode): FileNode = FileNode(
    id = v.id,
    packageId = v.packageId,
    ports = v.ports,
    mutPorts = v.mutPorts,
    declarationSurface = fromDeclarationSurface(v.declarationSurface)
  )

  def toExportGraph(v: ExportGraph): WExportGraph =
    models.ExportGraph(
      (
        nodes = v.packages.nodes.toSeq.sortBy(_.id).map(toPackageNode),
        edges = v.packages.edges.toSeq
          .sortBy(e => (e.source, e.target, e.weight))
          .map(toEdge)
      ),
      (
        nodes = v.files.nodes.toSeq.sortBy(_.id).map(toFileNode),
        edges = v.files.edges.toSeq
          .sortBy(e => (e.source, e.target, e.weight))
          .map(toEdge)
      )
    )
  def fromExportGraph(v: WExportGraph): ExportGraph =
    ExportGraph(
      PackageGraph(
        v.packages.nodes.map(fromPackageNode).toSet,
        v.packages.edges.map(fromEdge).toSet
      ),
      FileGraph(
        v.files.nodes.map(fromFileNode).toSet,
        v.files.edges.map(fromEdge).toSet
      )
    )

}
