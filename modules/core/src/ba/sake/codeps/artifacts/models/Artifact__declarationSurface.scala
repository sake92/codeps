package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class Artifact__declarationSurface(public: Option[Long], `protected`: Option[Long], packageRestricted: Option[Long], privateMembers: Option[Long], publicMutable: Option[Long], protectedMutable: Option[Long], packageRestrictedMutable: Option[Long], privateMutable: Option[Long]) derives JsonRW