package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Propagator(node: String, fanIn: Int, fanOut: Int, score: Double) derives JsonRW
object Propagator { given Validator[Propagator] = Validator.derived[Propagator].min(_.fanIn, 0).max(_.fanIn, 2147483647).min(_.fanOut, 0).max(_.fanOut, 2147483647) }