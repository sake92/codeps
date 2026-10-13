package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__cut(edge: Seq[String], weight: Long) derives JsonRW
object Artifact__cut { given Validator[Artifact__cut] = Validator.derived[Artifact__cut].minItems(_.edge, 2).maxItems(_.edge, 2) }