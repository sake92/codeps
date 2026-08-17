package ba.sake.codeps.model

import ba.sake.tupson.{JsonRW, ParseError, ParsingException}
import org.typelevel.jawn.ast.{JObject, JString, JValue}

/**
  * A directed dependency edge between two node ids. `weight` is the number of
  * finer-grained references this edge represents: 1 at parser level, summed
  * when aggregation/collapse maps several edges onto the same pair.
  * JSON: `{"source": ..., "target": ..., "weight": ...}` — `weight` defaults
  * to 1 when absent (backward compat with producers that don't emit it).
  */
case class Edge(source: String, target: String, weight: Int = 1)

object Edge:

  /** Manual instance: tupson's derived macro cannot fall back to case-class
    * defaults for missing keys, so the optional `weight` is handled explicitly.
    */
  given JsonRW[Edge] with
    override def write(value: Edge): JValue =
      val members = scala.collection.mutable.Map[String, JValue](
        "source" -> JsonRW[String].write(value.source),
        "target" -> JsonRW[String].write(value.target),
        "weight" -> JsonRW[Int].write(value.weight)
      )
      JObject(members)

    override def parse(path: String, jValue: JValue): Edge = jValue match
      case JObject(map) =>
        Edge(
          requiredString(map, path, "source"),
          requiredString(map, path, "target"),
          map.get("weight") match
            case None    => 1
            case Some(v) => JsonRW[Int].parse(s"$path.weight", v)
        )
      case other =>
        throw ParsingException(
          ParseError(path, s"should be Object but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
        )

    private def requiredString(map: scala.collection.mutable.Map[String, JValue], path: String, key: String): String =
      map.get(key) match
        case Some(JString(s)) => s
        case Some(other) =>
          throw ParsingException(
            ParseError(s"$path.$key", s"should be String but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
          )
        case None => throw ParsingException(ParseError(s"$path.$key", "is missing"))
