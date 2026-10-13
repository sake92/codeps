package ba.sake.codeps.artifacts

import java.net.URI
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.{
  InputFormat,
  JsonSchema,
  JsonSchemaFactory,
  SchemaLocation,
  SchemaValidatorsConfig,
  SpecVersion
}
import ba.sake.tupson.{*, given}
import org.typelevel.jawn.ast.JValue
import scala.jdk.CollectionConverters.*

/** Generated models own the wire shape; schemas enforce required fields and
  * omission rules. This adapter supports the references, intersections and
  * nullable unions in our artifact contracts.
  */
private[codeps] object ArtifactCodec {
  private val mapper = new ObjectMapper()
  private val names = Seq(
    "definitions",
    "export",
    "report",
    "history",
    "findings",
    "inspect-node",
    "inspect-cycle"
  )
  private val documents = names.map { name =>
    val resource = s"schemas/$name.schema.json"
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(
        throw new IllegalStateException(s"Missing artifact schema: $resource")
      )
    val json = try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
    mapper.readTree(json).get("$id").asText() -> json
  }.toMap
  private val trees = documents.view.mapValues(mapper.readTree(_)).toMap
  private val factory = JsonSchemaFactory.getInstance(
    SpecVersion.VersionFlag.V202012,
    builder => {
      builder.schemaLoaders(loaders => {
        loaders.schemas(documents.asJava); ()
      });
      ()
    }
  )
  private val config =
    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
  private val validators =
    scala.collection.concurrent.TrieMap.empty[String, JsonSchema]

  private case class Cursor(
      document: String,
      pointer: String,
      schema: JsonNode
  ) {
    def location: String =
      document + (if pointer.isEmpty then "" else "#" + pointer)
    def child(parts: String*): Cursor = parts.foldLeft(this) { (cursor, part) =>
      val node = if cursor.schema.isArray then cursor.schema.get(part.toInt)
      else cursor.schema.get(part)
      require(
        node != null,
        s"Missing schema node below ${cursor.location}: $part"
      )
      Cursor(
        document,
        cursor.pointer + "/" + part.replace("~", "~0").replace("/", "~1"),
        node
      )
    }
  }

  def mapped[D, W: JsonRW](schema: String)(
      toWire: D => W,
      fromWire: W => D,
      migrate: JValue => JValue = identity
  ): JsonRW[D] = new JsonRW[D] {
    override def write(value: D): JValue = {
      val json = transform(
        mapper.readTree(toWire(value).toJson),
        Seq(root(schema)),
        defaults = false
      )
      validate(root(schema), mapper.writeValueAsString(json))
      mapper.writeValueAsString(json).parseJson[JValue]
    }
    override def parse(path: String, value: JValue): D = {
      try {
        val json = migrate(value).render()
        val cursor = root(schema)
        validate(cursor, json)
        val prepared =
          transform(mapper.readTree(json), Seq(cursor), defaults = true)
        fromWire(mapper.writeValueAsString(prepared).parseJson[W])
      } catch {
        case error: TupsonException => throw error
        case error: Exception       =>
          throw TupsonException(s"$path: ${error.getMessage}")
      }
    }
  }

  private def root(schema: String): Cursor = {
    val uri = URI.create("https://sake92.github.io/codeps/schemas/" + schema)
    val document = uri.toString.takeWhile(_ != '#')
    val pointer = Option(uri.getFragment).getOrElse("")
    Cursor(
      document,
      pointer,
      if pointer.isEmpty then trees(document) else trees(document).at(pointer)
    )
  }

  private def validator(cursor: Cursor): JsonSchema =
    validators.getOrElseUpdate(
      cursor.location,
      factory.getSchema(SchemaLocation.of(cursor.location), config)
    )

  private def validate(cursor: Cursor, json: String): Unit = {
    val errors =
      validator(cursor).validate(json, InputFormat.JSON).asScala.toSeq
    if errors.nonEmpty then
      throw TupsonException(errors.take(3).map(_.toString).mkString("; "))
  }

  private def expanded(
      cursor: Cursor,
      value: JsonNode,
      seen: Set[String] = Set.empty
  ): Seq[Cursor] = {
    require(
      !seen(cursor.location),
      s"Recursive artifact schema: ${cursor.location}"
    )
    val next = seen + cursor.location
    val refs = Option(cursor.schema.get("$ref")).toSeq.flatMap { ref =>
      val uri = URI.create(cursor.document).resolve(ref.asText())
      val document = uri.toString.takeWhile(_ != '#')
      val pointer = Option(uri.getFragment).getOrElse("")
      expanded(
        Cursor(
          document,
          pointer,
          if pointer.isEmpty then trees(document)
          else trees(document).at(pointer)
        ),
        value,
        next
      )
    }
    val intersections = Option(cursor.schema.get("allOf")).toSeq
      .flatMap(_.elements().asScala.zipWithIndex)
      .flatMap { (_, index) =>
        expanded(cursor.child("allOf", index.toString), value, next)
      }
    val unions = Option(cursor.schema.get("anyOf")).toSeq.flatMap { members =>
      if value.isNull then Seq.empty
      else {
        val branches = members
          .elements()
          .asScala
          .zipWithIndex
          .filter { (branch, _) =>
            !Option(branch.get("type"))
              .exists(node => node.isTextual && node.asText() == "null")
          }
          .toSeq
        require(
          branches.size == 1,
          s"Expected one non-null branch at ${cursor.location}"
        )
        branches.flatMap { (_, index) =>
          expanded(cursor.child("anyOf", index.toString), value, next)
        }
      }
    }
    Seq(cursor) ++ refs ++ intersections ++ unions
  }

  private def transform(
      value: JsonNode,
      constraints: Seq[Cursor],
      defaults: Boolean
  ): JsonNode = {
    val schemas = constraints.flatMap(expanded(_, value))
    if value.isObject then {
      val result = value.deepCopy[ObjectNode]()
      val required = schemas
        .flatMap(cursor =>
          Option(cursor.schema.get("required")).toSeq
            .flatMap(_.elements().asScala.map(_.asText()))
        )
        .toSet
      val properties = schemas
        .flatMap { cursor =>
          Option(cursor.schema.get("properties")).toSeq
            .flatMap(_.fieldNames().asScala)
            .map(name => name -> cursor.child("properties", name))
        }
        .groupMap(_._1)(_._2)
      if defaults then
        properties.foreach { (name, children) =>
          if !result.has(name) && !required(name) then {
            val values = children
              .flatMap(cursor => Option(cursor.schema.get("default")))
              .distinct
            require(values.size <= 1, s"Ambiguous artifact default: $name")
            values.headOption.foreach(default =>
              result.set[JsonNode](name, default.deepCopy[JsonNode]())
            )
          }
        }
      result.fields().asScala.toSeq.foreach { entry =>
        properties.get(entry.getKey).foreach { children =>
          val invalidNull = entry.getValue.isNull && children.exists(cursor =>
            !validator(cursor).validate("null", InputFormat.JSON).isEmpty
          )
          if !defaults && invalidNull && !required(entry.getKey) then
            result.remove(entry.getKey)
          else
            result.set[JsonNode](
              entry.getKey,
              transform(entry.getValue, children, defaults)
            )
        }
      }
      result
    } else if value.isArray then {
      val items = schemas
        .filter(cursor => Option(cursor.schema.get("items")).exists(_.isObject))
        .map(_.child("items"))
      val result = mapper.createArrayNode()
      value
        .elements()
        .asScala
        .foreach(item => result.add(transform(item, items, defaults)))
      result
    } else if value.isFloatingPointNumber && value
        .decimalValue()
        .stripTrailingZeros()
        .scale() <= 0
    then {
      mapper.getNodeFactory.numberNode(value.decimalValue().toBigIntegerExact())
    } else value
  }
}
