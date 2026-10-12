package ba.sake.codeps.cli

import com.networknt.schema.{InputFormat, JsonSchemaFactory, SchemaLocation, SchemaValidatorsConfig, SpecVersion}
import scala.jdk.CollectionConverters.*

/** All schema references resolve from the checkout; tests never fetch the public site. */
private[cli] object ArtifactSchemas:
  private val directory = os.pwd / "docs" / "static" / "schemas"
  private val documents = os.list(directory).filter(_.ext == "json").map { path =>
    s"https://sake92.github.io/codeps/schemas/${path.last}" -> os.read(path)
  }.toMap
  private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
    builder => { builder.schemaLoaders(loaders => { loaders.schemas(documents.asJava); () }); () })
  private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

  def errors(schema: String, json: String): Seq[String] =
    factory.getSchema(SchemaLocation.of(s"https://sake92.github.io/codeps/schemas/$schema"), config)
      .validate(json, InputFormat.JSON).asScala.toSeq.map(_.toString)

  def definitionErrors(definition: String, json: String): Seq[String] =
    errors(s"definitions.schema.json#/$$defs/$definition", json)

  def metaErrors: Seq[String] =
    val meta = factory.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"), config)
    documents.toSeq.flatMap { (name, json) =>
      meta.validate(json, InputFormat.JSON).asScala.map(error => s"$name: $error")
    }
