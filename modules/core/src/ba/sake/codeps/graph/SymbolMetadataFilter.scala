package ba.sake.codeps.graph

import ba.sake.codeps.model.*

/** Keeps optional SemanticDB metadata aligned with a filtered node universe.
  * Symbol references are file-sourced, so a reference is evidence for a report
  * only when its source file and declared target both remain in the universe.
  */
private[graph] object SymbolMetadataFilter:

  def apply(graph: DepsGraph, nodes: Set[Node], restrictSources: Boolean): DepsGraph =
    if !restrictSources then graph.copy(nodes = nodes)
    else
      val retainedSources = nodes.iterator.flatMap(sourceFiles).toSet
      val retainedSymbols = nodes.iterator
        .filter(n => (n.kind == NodeKind.`type` || n.kind == NodeKind.member) && n.isExposed)
        .map(_.id)
        .toSet ++ graph.declaredPublicSymbols.toSeq.flatMap(_.collect {
          case (symbol, sourceFile) if retainedSources.contains(sourceFile) => symbol
        })
      val references = graph.symbolReferences.map(_.filter { reference =>
        retainedSources.contains(reference.sourceFile) && retainedSymbols.contains(reference.targetSymbol)
      })
      val declarations = graph.declaredPublicSymbols.map(_.filter {
        case (_, sourceFile) => retainedSources.contains(sourceFile)
      })
      graph.copy(nodes = nodes, symbolReferences = references, declaredPublicSymbols = declarations)

  private def sourceFiles(node: Node): Iterator[String] =
    (if node.kind == NodeKind.file then Iterator.single(node.id) else Iterator.empty) ++ node.file.iterator
