package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps metrics report v4`(schemaVersion: 4, packages: `Codeps metrics report v4__packages`, files: Option[`Codeps metrics report v4__files`]) derives JsonRW
case class `Codeps metrics report v4__packages`(schemaVersion: 3, scope: "packages", generatedAt: Instant, summary: `Shared codeps artifact definitions__summary`, cycles: Seq[`Shared codeps artifact definitions__cycle`], propagators: Seq[`Shared codeps artifact definitions__propagator`], surface: Seq[`Shared codeps artifact definitions__surface`], orphans: Seq[String], findings: Seq[`Shared codeps artifact definitions__finding`], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object `Codeps metrics report v4__packages` { given Validator[`Codeps metrics report v4__packages`] = Validator.derived[`Codeps metrics report v4__packages`].maxItems(_.findings, 10000) }
case class `Codeps metrics report v4__files`(schemaVersion: 3, scope: "files", generatedAt: Instant, summary: `Shared codeps artifact definitions__summary`, cycles: Seq[`Shared codeps artifact definitions__cycle`], propagators: Seq[`Shared codeps artifact definitions__propagator`], surface: Seq[`Shared codeps artifact definitions__surface`], orphans: Seq[String], findings: Seq[`Shared codeps artifact definitions__finding`], truncation: Option[(findingsOmitted: Long)]) derives JsonRW
object `Codeps metrics report v4__files` { given Validator[`Codeps metrics report v4__files`] = Validator.derived[`Codeps metrics report v4__files`].maxItems(_.findings, 10000) }