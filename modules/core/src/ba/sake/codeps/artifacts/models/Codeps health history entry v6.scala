package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps health history entry v6`(schemaVersion: 6, at: Instant, commit: String, packages: `Shared codeps artifact definitions__healthSnapshot`, files: Option[`Shared codeps artifact definitions__healthSnapshot`]) derives JsonRW