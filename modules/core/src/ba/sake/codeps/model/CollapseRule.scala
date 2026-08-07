package ba.sake.codeps.model

enum CollapseRule:
  /** "com.example.**" — everything below prefix collapses into the prefix. */
  case Wild(prefix: String)

  /** "org.lib.*" — sub-packages collapse into the next level below prefix. */
  case SingleLevel(prefix: String)

  def prefixLength: Int = this match
    case Wild(p)        => p.length
    case SingleLevel(p) => p.length

  /** Result of applying this rule to a package; None if the rule does not match. */
  def apply(pkg: String): Option[String] = this match
    case Wild(prefix) =>
      if pkg == prefix || pkg.startsWith(prefix + ".") then Some(prefix) else None
    case SingleLevel(prefix) =>
      if pkg.startsWith(prefix + ".") then
        val rest  = pkg.drop(prefix.length + 1)
        val level = rest.takeWhile(_ != '.')
        Some(s"$prefix.$level")
      else None

object CollapseRule:
  /** Only trailing wildcards are supported: `prefix.**` or `prefix.*`. */
  def parse(pattern: String): Either[String, CollapseRule] =
    if pattern.endsWith(".**") then
      val prefix = pattern.dropRight(3)
      if prefix.isEmpty then Left(s"collapse rule prefix must not be empty: $pattern")
      else Right(Wild(prefix))
    else if pattern.endsWith(".*") then
      val prefix = pattern.dropRight(2)
      if prefix.isEmpty then Left(s"collapse rule prefix must not be empty: $pattern")
      else Right(SingleLevel(prefix))
    else Left(s"collapse rule must end with '**' or '*': $pattern")
