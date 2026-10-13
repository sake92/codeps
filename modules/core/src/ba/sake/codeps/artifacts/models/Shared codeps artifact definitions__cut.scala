package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__cut`(edge: Seq[String], weight: Long) derives JsonRW
object `Shared codeps artifact definitions__cut` { given Validator[`Shared codeps artifact definitions__cut`] = Validator.derived[`Shared codeps artifact definitions__cut`].minItems(_.edge, 2).maxItems(_.edge, 2) }