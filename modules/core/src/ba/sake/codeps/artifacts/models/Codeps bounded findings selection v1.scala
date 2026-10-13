package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps bounded findings selection v1`(schemaVersion: 1, project: String, scope: "packages" | "files", generatedAt: Instant, total: Long, omitted: Long, sourceFindingsOmitted: Long, findings: Seq[`Shared codeps artifact definitions__finding`]) derives JsonRW
object `Codeps bounded findings selection v1` { given Validator[`Codeps bounded findings selection v1`] = Validator.derived[`Codeps bounded findings selection v1`].min(_.total, 0L).min(_.omitted, 0L).min(_.sourceFindingsOmitted, 0L).maxItems(_.findings, 10000) }