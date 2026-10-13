package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__propagator(node: String, fanIn: Long, fanOut: Long, score: Double) derives JsonRW
object Artifact__propagator { given Validator[Artifact__propagator] = Validator.derived[Artifact__propagator].min(_.fanIn, 0L).min(_.fanOut, 0L) }