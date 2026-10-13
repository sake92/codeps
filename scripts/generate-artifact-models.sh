#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

exec cs launch -r https://central.sonatype.com/repository/maven-snapshots \
  ba.sake:openapi4s-cli_2.13:0.9.0-14-007c57-SNAPSHOT \
  -M ba.sake.openapi4s.cli.OpenApi4sMain -- \
  --models tupson --url docs/static/schemas \
  --baseFolder modules/core/src --basePackage ba.sake.codeps.artifacts
