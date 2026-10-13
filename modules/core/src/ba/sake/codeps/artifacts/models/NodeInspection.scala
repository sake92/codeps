package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class NodeInspection(id: String, surface: Surface, cycleId: Option[String], findings: Seq[Finding]) derives JsonRW