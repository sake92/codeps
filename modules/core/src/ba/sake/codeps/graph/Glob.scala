package ba.sake.codeps.graph

/** Minimal glob matcher for file paths. `matches(pattern)(path)` compiles the pattern to a
  * regex and matches the whole path. A `**` followed by `/` matches zero or more whole
  * path segments, `**` crosses `/`, `*` matches any run of non-`/` characters,
  * `?` matches one non-`/` character, everything else is literal. No dependency — a few
  * regex building blocks. */
object Glob:

  def matches(pattern: String)(path: String): Boolean =
    java.util.regex.Pattern.matches(compile(pattern), path)

  private def compile(pattern: String): String =
    val sb = new StringBuilder("^")
    var i = 0
    while i < pattern.length do
      pattern(i) match
        case '*' if i + 2 < pattern.length && pattern(i + 1) == '*' && pattern(i + 2) == '/' =>
          sb.append("(?:[^/]+/)*"); i += 3
        case '*' if i + 1 < pattern.length && pattern(i + 1) == '*' =>
          sb.append(".*"); i += 2
        case '*' => sb.append("[^/]*"); i += 1
        case '?' => sb.append("[^/]"); i += 1
        case _ =>
          val start = i
          while i < pattern.length && pattern(i) != '*' && pattern(i) != '?' do i += 1
          sb.append(java.util.regex.Pattern.quote(pattern.substring(start, i)))
    sb.append("$")
    sb.toString
