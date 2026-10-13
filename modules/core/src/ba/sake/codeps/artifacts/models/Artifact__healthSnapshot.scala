package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__healthSnapshot(status: "critical" | "unhealthy" | "needs-attention" | "healthy" | "excellent", health: (score: Double, status: "critical" | "unhealthy" | "needs-attention" | "healthy" | "excellent", factors: (cycles: Double, mutableSurface: Double, exposedSurface: Double, structuralUse: Double, propagators: Double)), structure: (nodes: Long, edges: Long), cycles: (count: Long, nodes: Long, largestScc: Long, internalEdges: Long), surface: Artifact__healthSnapshot__surface, findings: (critical: Long, high: Long, medium: Long, low: Long)) derives JsonRW
case class Artifact__healthSnapshot__surface(publicSurface: Double, publicMutableSurface: Double, totalDeclaredSurface: Double, encapsulationRatio: Option[Double]) derives JsonRW
object Artifact__healthSnapshot__surface { given Validator[Artifact__healthSnapshot__surface] = Validator.derived[Artifact__healthSnapshot__surface].min(_.publicSurface, 0.0d).min(_.publicMutableSurface, 0.0d).min(_.totalDeclaredSurface, 0.0d) }