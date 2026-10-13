package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Cut(edge: Seq[String], weight: Int) derives JsonRW
object Cut { given Validator[Cut] = Validator.derived[Cut].minItems(_.edge, 2).maxItems(_.edge, 2).min(_.weight, -2147483648).max(_.weight, 2147483647) }