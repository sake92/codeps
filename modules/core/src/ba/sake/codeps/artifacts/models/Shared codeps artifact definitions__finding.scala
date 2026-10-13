package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Shared codeps artifact definitions__finding`(id: String, kind: "cycle" | "propagator" | "mutableSurface" | "structuralUse", severity: "critical" | "high" | "medium" | "low", subject: String, evidence: String, confidence: "high" | "structuralProxy", nextAction: String) derives JsonRW