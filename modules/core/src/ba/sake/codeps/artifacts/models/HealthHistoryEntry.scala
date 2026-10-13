package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class HealthHistoryEntry(schemaVersion: 6, at: Instant, commit: String, packages: Artifact__healthSnapshot, files: Option[Artifact__healthSnapshot]) derives JsonRW