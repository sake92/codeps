package ba.sake.codeps.jdeps

import ba.sake.codeps.model.{PackageEdge, PkgStats}

object JdepsParser:

  private val PackageRegex = "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*".r

  /**
    * Parses `jdeps -verbose:package` text output.
    * Summary lines ("classes -> java.base") are not indented and are skipped;
    * detail lines are indented: "   pkg.a -> pkg.b   archive".
    * jdeps carries no per-package file/class info, so the stats map is empty.
    */
  def parse(content: String): (Set[String], Set[PackageEdge], Map[String, PkgStats]) =
    var own   = Set.empty[String]
    var edges = Set.empty[PackageEdge]
    for line <- content.linesIterator do
      parseLine(line).foreach { case (src, tgt) =>
        own += src
        edges += PackageEdge(src, tgt)
      }
    (own, edges, Map.empty)

  private def parseLine(line: String): Option[(String, String)] =
    if line.isEmpty || !line.head.isWhitespace then None
    else
      line.split(" -> ") match
        case Array(left, right) =>
          val src = left.trim
          val tgt = right.trim.split("\\s+").head
          if isPackage(src) && isPackage(tgt) then Some((src, tgt)) else None
        case _ => None

  private def isPackage(s: String): Boolean = PackageRegex.matches(s)
