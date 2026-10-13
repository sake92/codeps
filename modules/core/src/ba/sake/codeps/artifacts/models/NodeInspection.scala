package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class NodeInspection(id: String, surface: Artifact__surface, cycleId: Option[String], findings: Seq[Artifact__finding]) derives JsonRW