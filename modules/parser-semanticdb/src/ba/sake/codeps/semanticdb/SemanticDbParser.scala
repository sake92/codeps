package ba.sake.codeps.semanticdb

import ba.sake.codeps.model.*
import scala.meta.internal.semanticdb.{TextDocument, TextDocuments}

object SemanticDbParser:

  /**
    * Parses the bytes of one .semanticdb file (a TextDocuments protobuf payload).
    * Returns the own packages defined in the file, the package edges
    * (own package -> referenced package, for every occurrence referencing
    * a symbol with package info), and per-package stats (file count = number of
    * documents, class count = class-like symbols: CLASS, OBJECT or TRAIT).
    */
  def parse(bytes: Array[Byte]): Either[String, (Set[String], Set[PackageEdge], Map[String, PkgStats])] =
    try
      val docs = TextDocuments.parseFrom(bytes)
      var own    = Set.empty[String]
      var edges  = Set.empty[PackageEdge]
      var counts = Map.empty[String, PkgStats]
      for doc <- docs.documents do
        deriveOwnPackage(doc).foreach { pkg =>
          own += pkg
          val classes = doc.symbols.count(s => s.kind.isClass || s.kind.isObject || s.kind.isTrait)
          counts = counts.updated(
            pkg,
            counts.get(pkg) match
              case Some(prev) => prev + PkgStats(1, classes)
              case None       => PkgStats(1, classes)
          )
          for occ <- doc.occurrences do
            packageOfSymbol(occ.symbol).foreach { ref =>
              if ref != pkg then edges += PackageEdge(pkg, ref)
            }
        }
      Right((own, edges, counts))
    catch
      case e: Exception => Left(s"failed to parse semanticdb: ${e.getMessage}")

  /** Package of a symbol: everything before the last '/', with '/' -> '.'. */
  private def packageOfSymbol(symbol: String): Option[String] =
    // symbols ending in '/' are package declarations (e.g. "com/example/util/"), not packages of symbols
    if symbol.endsWith("/") then None
    else
      val idx = symbol.lastIndexOf('/')
      if idx < 0 then None
      else Some(symbol.substring(0, idx).replace('/', '.'))

  /** Own package from the document's defined symbols; falls back to its URI path. */
  private def deriveOwnPackage(doc: TextDocument): Option[String] =
    doc.symbols.iterator.map(s => s.symbol).flatMap(packageOfSymbol).nextOption()
      .orElse(uriPackage(doc.uri))

  private def uriPackage(uri: String): Option[String] =
    val idx = uri.lastIndexOf('/')
    if idx < 0 then Some("_empty_") // default package
    else Some(uri.substring(0, idx).replace('/', '.'))
