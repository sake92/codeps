package ba.sake.codeps.semanticdb

import ba.sake.codeps.model.*
import scala.meta.internal.semanticdb.{TextDocument, TextDocuments}

object SemanticDbParser:

  /**
    * Parses the bytes of one .semanticdb file (a TextDocuments protobuf payload).
    * Returns the own packages defined in the file and the package edges
    * (own package -> referenced package, for every occurrence referencing
    * a symbol with package info).
    */
  def parse(bytes: Array[Byte]): Either[String, (Set[String], Set[PackageEdge])] =
    try
      val docs = TextDocuments.parseFrom(bytes)
      var own   = Set.empty[String]
      var edges = Set.empty[PackageEdge]
      for doc <- docs.documents do
        deriveOwnPackage(doc).foreach { pkg =>
          own += pkg
          for occ <- doc.occurrences do
            packageOfSymbol(occ.symbol).foreach { ref =>
              if ref != pkg then edges += PackageEdge(pkg, ref)
            }
        }
      Right((own, edges))
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
