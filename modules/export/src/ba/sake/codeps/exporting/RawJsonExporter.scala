package ba.sake.codeps.exporting

import ba.sake.codeps.model.PackageDeps
import ba.sake.tupson.{*, given}

object RawJsonExporter:

  /**
    * Serializes parsed dependency info (own packages, edges, stats) into the
    * common JSON input format consumed by the `json` subcommand.
    * Emits after filtering, before collapsing (collapse destroys edges).
    */
  def render(deps: PackageDeps): String =
    deps.toJson(spaces = 2, sort = true)
