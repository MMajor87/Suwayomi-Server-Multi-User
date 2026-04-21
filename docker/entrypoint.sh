#!/bin/sh
# Suwayomi-Server container entrypoint.
# Extend JVM behaviour via JAVA_OPTS without losing the fixed system properties.
# Example: -e JAVA_OPTS="-Xmx1g -Dsuwayomi.tachidesk.config.server.port=8080"
set -e

# shellcheck disable=SC2086
exec java \
  -Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false \
  -Dsuwayomi.tachidesk.config.server.rootDir=/data \
  ${JAVA_OPTS:-} \
  -jar /app/server.jar
