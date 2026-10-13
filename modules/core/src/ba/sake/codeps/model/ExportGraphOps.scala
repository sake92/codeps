package ba.sake.codeps.model

import ba.sake.codeps.artifacts.models

/** Exported package/file views become the node kinds and weighted edges used by
  * graph algorithms.
  */
object ExportGraphOps {
  extension (graph: models.ExportGraph) {
    def packageDeps: DepsGraph = DepsGraph(
      graph.packages.nodes
        .map(n =>
          Node(
            n.id,
            NodeKind.`package`,
            ports = n.ports,
            mutPorts = n.mutPorts,
            declarationSurface = surface(n.declarationSurface)
          )
        )
        .toSet,
      edges(graph.packages.edges)
    )

    def fileDeps: DepsGraph = {
      val files = graph.files.nodes
        .map(n =>
          Node(
            n.id,
            NodeKind.file,
            n.packageId,
            ports = n.ports,
            mutPorts = n.mutPorts,
            declarationSurface = surface(n.declarationSurface)
          )
        )
        .toSet
      val packages = graph.files.nodes
        .flatMap(_.packageId)
        .map(id => Node(id, NodeKind.`package`))
        .toSet
      DepsGraph(files ++ packages, edges(graph.files.edges))
    }
  }

  private[codeps] def declarations(
      value: DeclarationSurface
  ): models.DeclarationSurface =
    models.DeclarationSurface(
      Some(value.public),
      Some(value.`protected`),
      Some(value.packageRestricted),
      Some(value.privateMembers),
      Some(value.publicMutable),
      Some(value.protectedMutable),
      Some(value.packageRestrictedMutable),
      Some(value.privateMutable)
    )

  private def surface(value: models.DeclarationSurface): DeclarationSurface =
    DeclarationSurface(
      value.public.getOrElse(0),
      value.`protected`.getOrElse(0),
      value.packageRestricted.getOrElse(0),
      value.privateMembers.getOrElse(0),
      value.publicMutable.getOrElse(0),
      value.protectedMutable.getOrElse(0),
      value.packageRestrictedMutable.getOrElse(0),
      value.privateMutable.getOrElse(0)
    )

  private def edges(values: Seq[models.Edge]): Set[Edge] =
    values.map(e => Edge(e.source, e.target, e.weight.getOrElse(1))).toSet
}
