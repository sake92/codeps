package ba.sake.codeps.artifacts.models
import java.time.*
import java.util.UUID
import org.typelevel.jawn.ast.JValue
import ba.sake.tupson.*
import ba.sake.validson.Validator
case class `Codeps export format`(packages: (nodes: Seq[`Shared codeps artifact definitions__packageNode`], edges: Seq[`Shared codeps artifact definitions__edge`]), files: (nodes: Seq[`Shared codeps artifact definitions__fileNode`], edges: Seq[`Shared codeps artifact definitions__edge`])) derives JsonRW