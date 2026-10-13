package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class MetricsReports(schemaVersion: 4, packages: ScopeReport, files: Option[ScopeReport]) derives JsonRW