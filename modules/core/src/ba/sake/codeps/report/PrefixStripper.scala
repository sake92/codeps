package ba.sake.codeps.report

/** Strips the longest common prefix from a collection of ids. */
object PrefixStripper:

  /** Returns (prefix, id -> strippedId). Prefix is None when no stripping happened (less than 2 ids, or empty). */
  def strip(ids: Iterable[String]): (Option[String], Map[String, String]) =
    strip(ids, '.')

  /** Returns (prefix, id -> strippedId), stopping only at a complete segment. */
  def strip(ids: Iterable[String], separator: Char): (Option[String], Map[String, String]) =
    val distinctIds = ids.toSeq.distinct.sorted
    if distinctIds.size < 2 then (None, Map.empty)
    else
      val rawPrefix = commonPrefix(distinctIds)
      val lastSeparator = rawPrefix.lastIndexOf(separator)
      val sepPrefix = if lastSeparator >= 0 then rawPrefix.substring(0, lastSeparator + 1) else ""
      if sepPrefix.isEmpty then (None, Map.empty)
      else
        val stripped = distinctIds.map { id =>
          val s = id.substring(sepPrefix.length)
          id -> (if s.isEmpty then id else s)
        }.toMap
        (Some(sepPrefix), stripped)

  private def commonPrefix(ids: Seq[String]): String =
    ids.reduce((a, b) => a.zip(b).takeWhile((x, y) => x == y).map(_._1).mkString)
