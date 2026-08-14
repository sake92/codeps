package ba.sake.codeps.jdeps

import ba.sake.codeps.model.{DepsGraph, Edge, Node, NodeKind}

object JdepsParser:

  /**
    * Parses `jdeps -verbose:class` text output.
    * Summary lines ("classes -> java.base") are non-indented and skipped; detail lines are
    * indented: "   com.example.Foo -> java.lang.String   java.base".
    * Own classes = the sources of detail lines; only edges between own classes are kept
    * (project-internal dependencies); JDK/library/`not found` targets are dropped.
    * Self-edges (e.g. `Outer -> Outer$`) are intentionally kept here; the analyzer's
    * consumers (Filter/Aggregator/Collapser/GraphBuilder) drop them later.
    * Inner classes map `$` -> `#`; Scala object classes lose their trailing `$`.
    */
  def parse(content: String): DepsGraph =
    val lines = content.linesIterator
      .filter(l => l.nonEmpty && l.head.isWhitespace)
      .flatMap(parseLine)
      .toSeq
    val ownClasses = lines.map(_._1).toSet
    val nodes = ownClasses.flatMap(typeNodeChain)
    val edges = lines.collect {
      case (src, tgt) if ownClasses.contains(tgt) => Edge(classId(src), classId(tgt))
    }.toSet
    DepsGraph(nodes, edges)

  private def parseLine(line: String): Option[(String, String)] =
    line.split(" -> ") match
      case Array(left, right) =>
        val src = left.trim
        val tgt = right.trim.split("\\s+").head
        if isClass(src) && isClass(tgt) then Some((src, tgt)) else None
      case _ => None

  // class names have at least one package separator
  private val ClassRegex = "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+".r
  private def isClass(s: String): Boolean = ClassRegex.matches(s)

  /** `com.example.app.Main$` -> `com.example.app.Main`; `com.example.Outer$Inner` -> `com.example.Outer#Inner`. */
  private def classId(fqcn: String): String =
    val parts = fqcn.split("\\$").filter(_.nonEmpty) // trailing `$` of Scala objects dropped
    parts.head + parts.tail.map("#" + _).mkString

  /** The type node plus its parent chain: enclosing types and the package. */
  private def typeNodeChain(fqcn: String): Set[Node] =
    val id = classId(fqcn)
    val idx = id.lastIndexOf('.')
    val pkg = id.substring(0, idx)
    val parts = id.substring(idx + 1).split("#").toSeq
    val b = Set.newBuilder[Node]
    b += Node(pkg, NodeKind.`package`)
    var parentId: Option[String] = Some(pkg)
    for part <- parts do
      val cur = parentId match
        case Some(p) if p == pkg => s"$p.$part"
        case Some(p)             => s"$p#$part"
        case None                => part
      b += Node(cur, NodeKind.`type`, parentId)
      parentId = Some(cur)
    b.result()
