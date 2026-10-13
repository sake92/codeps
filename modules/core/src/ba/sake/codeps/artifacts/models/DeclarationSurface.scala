package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class DeclarationSurface(public: Option[Int], `protected`: Option[Int], packageRestricted: Option[Int], privateMembers: Option[Int], publicMutable: Option[Int], protectedMutable: Option[Int], packageRestrictedMutable: Option[Int], privateMutable: Option[Int]) derives JsonRW