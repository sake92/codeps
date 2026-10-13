package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__scopeReport`(schemaVersion: 3, scope: "packages" | "files", generatedAt: Instant, summary: `Shared codeps artifact definitions__summary`, cycles: Seq[`Shared codeps artifact definitions__cycle`], propagators: Seq[`Shared codeps artifact definitions__propagator`], surface: Seq[`Shared codeps artifact definitions__surface`], orphans: Seq[String], findings: Seq[`Shared codeps artifact definitions__finding`], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object `Shared codeps artifact definitions__scopeReport` { given Validator[`Shared codeps artifact definitions__scopeReport`] = Validator.derived[`Shared codeps artifact definitions__scopeReport`].maxItems(_.findings, 10000) }