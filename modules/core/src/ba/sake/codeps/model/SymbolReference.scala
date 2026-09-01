package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** One SemanticDB reference occurrence to a declared symbol. The source is a
  * file id; occurrences intentionally remain a sequence so repeated references
  * from one consumer contribute to `referenceCount` while consumers are
  * deduplicated by the metrics layer. */
case class SymbolReference(sourceFile: String, targetSymbol: String) derives JsonRW
