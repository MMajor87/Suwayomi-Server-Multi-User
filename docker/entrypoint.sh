#!/bin/sh
# Suwayomi-Server container entrypoint.
# Extend JVM behaviour via JAVA_OPTS without losing the fixed system properties.
# Example: -e JAVA_OPTS="-Xmx1g -Dsuwayomi.tachidesk.config.server.port=8080"
#
# EXTENSION_REPOS: comma-separated list of extension repository URLs to pre-configure.
# Example: -e EXTENSION_REPOS="https://github.com/MY_ACCOUNT/MY_REPO/tree/repo,https://other.repo/"
#
# SOURCE_HOST_OVERRIDES: comma-separated host overrides in old.host=new.host form.
# Example: -e SOURCE_HOST_OVERRIDES="bato.to=xbato.co.uk,www.bato.to=xbato.co.uk"
set -e

# Build the positional arg list for java, starting with fixed properties.
set -- \
  -Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false \
  -Dsuwayomi.tachidesk.config.server.rootDir=/data

# Convert EXTENSION_REPOS (comma-separated URLs) into a JSON array and append
# the JVM property only when the variable is non-empty.
if [ -n "${EXTENSION_REPOS:-}" ]; then
    json_array="["
    first=true
    IFS=','
    for url in $EXTENSION_REPOS; do
        # Strip surrounding whitespace.
        url=$(printf '%s' "$url" | tr -d ' ')
        [ -z "$url" ] && continue
        if [ "$first" = "true" ]; then
            first=false
        else
            json_array="${json_array},"
        fi
        json_array="${json_array}\"${url}\""
    done
    unset IFS
    json_array="${json_array}]"
    set -- "$@" "-Dsuwayomi.tachidesk.config.server.extensionRepos=${json_array}"
fi

# Pass optional host overrides to rewrite source request hosts at runtime.
if [ -n "${SOURCE_HOST_OVERRIDES:-}" ]; then
    set -- "$@" "-Dsuwayomi.tachidesk.config.server.sourceHostOverrides=${SOURCE_HOST_OVERRIDES}"
fi

# JAVA_OPTS is intentionally unquoted so multiple flags word-split correctly.
# shellcheck disable=SC2086
exec java "$@" ${JAVA_OPTS:-} -jar /app/server.jar
