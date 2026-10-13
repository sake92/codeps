package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps node inspection`(id: String, surface: `Shared codeps artifact definitions__surface`, cycleId: Option[String], findings: Seq[`Shared codeps artifact definitions__finding`]) derives JsonRW