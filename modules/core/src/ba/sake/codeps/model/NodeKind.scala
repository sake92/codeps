package ba.sake.codeps.model

import ba.sake.tupson.JsonRW

/** Kind of a dependency-graph node: package, file, type or member. */
enum NodeKind derives JsonRW:
  case `package`, file, `type`, member
