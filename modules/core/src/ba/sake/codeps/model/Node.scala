package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** A node in the dependency graph.
  * `parentId` is the nearest enclosing node: for `type` nodes their package or enclosing
  * type, for `member` nodes their type or package. `package` and `file` nodes are
  * standalone (`parentId` is None). `file` is the source file of a `type`/`member`,
  * equal to the id of its `file` node.
  */
case class Node(
    id: String,
    kind: NodeKind,
    parentId: Option[String] = None,
    file: Option[String] = None
) derives JsonRW:

  /** The id of the topmost package ancestor of this node (walking `parentId` chains), if it has one. */
  def rootPackageId(nodesById: Map[String, Node]): Option[String] =
    if kind == NodeKind.`package` then Some(id)
    else
      var cur = parentId
      var visited = Set.empty[String]
      while cur.nonEmpty && !visited.contains(cur.get) do
        visited += cur.get
        nodesById.get(cur.get) match
          case Some(p) if p.kind == NodeKind.`package` => return Some(p.id)
          case Some(p)                                 => cur = p.parentId
          case None                                    => cur = None
      None // unreachable package ancestor or cyclic parentId chain
