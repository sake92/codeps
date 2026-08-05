package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object Filter:
  /**
    * Node universe = own packages matching an include pattern,
    * minus packages matching an exclude pattern (exclude wins).
    * A pattern `ba.sake` matches `ba.sake` itself and everything below it.
    * Edges are kept only when both endpoints are in the universe; self-edges dropped.
    */
  def apply(
      ownPackages: Set[String],
      edges: Set[PackageEdge],
      includes: Seq[String],
      excludes: Seq[String]
  ): (Set[String], Set[PackageEdge]) =
    val universe = ownPackages
      .filter(pkg => includes.exists(matches(pkg, _)))
      .filterNot(pkg => excludes.exists(matches(pkg, _)))
    val kept = edges.filter { e =>
      universe.contains(e.source) && universe.contains(e.target) && e.source != e.target
    }
    (universe, kept)

  private def matches(pkg: String, pattern: String): Boolean =
    pkg == pattern || pkg.startsWith(pattern + ".")
