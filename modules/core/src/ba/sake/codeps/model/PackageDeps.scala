package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/**
  * The result of parsing dependency info: the project's own packages, package-level
  * dependency edges (endpoints may be outside `own`), and optional per-package stats.
  *
  * This is also the common JSON input format consumed by the `json` subcommand:
  * `{"own": [...], "edges": [{"source": "...", "target": "..."}], "stats": {...}}`.
  * Missing `own`/`edges` default to empty; unknown keys are ignored.
  */
case class PackageDeps(
    own: Set[String],
    edges: Set[PackageEdge],
    stats: Map[String, PkgStats] = Map.empty
) derives JsonRW:

  /** Merges two results: unions own packages/edges and sums per-package stats. */
  def merge(other: PackageDeps): PackageDeps =
    PackageDeps(
      own ++ other.own,
      edges ++ other.edges,
      other.stats.foldLeft(stats) { case (acc, (k, v)) =>
        acc.get(k) match
          case Some(prev) => acc.updated(k, prev + v)
          case None       => acc + (k -> v)
      }
    )

object PackageDeps:
  val empty: PackageDeps = PackageDeps(Set.empty, Set.empty)
