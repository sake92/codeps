package ba.sake.codeps.report

import ba.sake.tupson.JsonRW

/** Grade of a cycle: fine = leave it, meh = worth a look, bad = should be broken. */
enum Severity derives JsonRW:
  case fine, meh, bad
