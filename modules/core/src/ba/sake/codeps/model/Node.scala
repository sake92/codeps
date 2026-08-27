package ba.sake.codeps.model

import ba.sake.tupson.{JsonRW, ParseError, ParsingException}
import org.typelevel.jawn.ast.{JObject, JString, JValue}

/** A node in the dependency graph.
  * `parentId` is the nearest enclosing node: for `type` nodes their package or enclosing
  * type, for `member` nodes their type or package. `package` and `file` nodes are
  * standalone (`parentId` is None). `file` is the source file of a `type`/`member`,
  * equal to the id of its `file` node.
  *
  * `isExposed`/`ports`/`mutPorts` are resolved by the extraction backend (the Scala
  * adapter in `SemanticDbParser`): `isExposed` = part of the externally visible surface,
  * `ports` = this node's own weighted exposure contribution, `mutPorts` = its own
  * mutable-state exposure contribution. The metrics layer only ever sums these up per
  * scope node — the weight rules (sealed/given/var/...) never leak past the adapter.
  */
case class Node(
    id: String,
    kind: NodeKind,
    parentId: Option[String] = None,
    file: Option[String] = None,
    isExposed: Boolean = true,
    ports: Double = 0.0,
    mutPorts: Double = 0.0
):

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

object Node:

  /** Manual instance: tupson's derived macro cannot fall back to case-class defaults
    * for missing keys, so the exposure fields (`isExposed`, `ports`, `mutPorts`)
    * default explicitly for backward compat with graphs that don't carry them.
    */
  given JsonRW[Node] with
    override def write(value: Node): JValue =
      val members = scala.collection.mutable.Map[String, JValue](
        "id" -> JsonRW[String].write(value.id),
        "kind" -> JsonRW[NodeKind].write(value.kind),
        "isExposed" -> JsonRW[Boolean].write(value.isExposed),
        "ports" -> JsonRW[Double].write(value.ports),
        "mutPorts" -> JsonRW[Double].write(value.mutPorts)
      )
      value.parentId.foreach(p => members("parentId") = JsonRW[String].write(p))
      value.file.foreach(f => members("file") = JsonRW[String].write(f))
      JObject(members)

    override def parse(path: String, jValue: JValue): Node = jValue match
      case JObject(map) =>
        Node(
          requiredString(map, path, "id"),
          JsonRW[NodeKind].parse(s"$path.kind", required(map, path, "kind")),
          optionalString(map, "parentId"),
          optionalString(map, "file"),
          map.get("isExposed") match
            case None    => true
            case Some(v) => JsonRW[Boolean].parse(s"$path.isExposed", v),
          map.get("ports") match
            case None    => 0.0
            case Some(v) => JsonRW[Double].parse(s"$path.ports", v),
          map.get("mutPorts") match
            case None    => 0.0
            case Some(v) => JsonRW[Double].parse(s"$path.mutPorts", v)
        )
      case other =>
        throw ParsingException(
          ParseError(path, s"should be Object but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
        )

    private def required(map: scala.collection.mutable.Map[String, JValue], path: String, key: String): JValue =
      map.getOrElse(key, throw ParsingException(ParseError(s"$path.$key", "is missing")))

    private def requiredString(map: scala.collection.mutable.Map[String, JValue], path: String, key: String): String =
      required(map, path, key) match
        case JString(s) => s
        case other =>
          throw ParsingException(
            ParseError(s"$path.$key", s"should be String but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
          )

    private def optionalString(map: scala.collection.mutable.Map[String, JValue], key: String): Option[String] =
      map.get(key) match
        case None            => None
        case Some(JString(s)) => Some(s)
        case Some(other) =>
          throw ParsingException(
            ParseError(s"$key", s"should be String but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
          )
