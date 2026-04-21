# ── Stage 1: Build WebUI ─────────────────────────────────────────────────────
# Uses the exact Node version the project declares as its engine requirement.
FROM node:22.12.0-alpine AS webui-build

WORKDIR /webui

# Install dependencies before copying sources for better layer caching.
COPY webUI/package.json webUI/yarn.lock ./
RUN npm install -g yarn && yarn install --frozen-lockfile

COPY webUI/ .
RUN yarn build

# ── Stage 2: Build server JAR ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-noble AS server-build

WORKDIR /build

# Download the Gradle distribution first — this layer is cached as long as
# gradle-wrapper.properties does not change.
COPY gradlew ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --version

# Copy build scripts, then sources.  Splitting these two COPY steps means a
# source-only change does not invalidate the dependency-resolution layer.
COPY build.gradle.kts settings.gradle.kts ./
COPY buildSrc/   buildSrc/
COPY server/     server/
COPY AndroidCompat/ AndroidCompat/

# Zip the pre-built WebUI and place it where processResources expects it.
# This bypasses the buildWebUIApp / bundleWebUI Gradle tasks entirely — no
# Node runtime is needed in this stage.
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends zip \
    && rm -rf /var/lib/apt/lists/*

COPY --from=webui-build /webui/build/ /tmp/webui-dist/
RUN mkdir -p server/src/main/resources \
    && cd /tmp/webui-dist \
    && zip -r /build/server/src/main/resources/WebUI.zip .

# Build the fat JAR.  bundleWebUI / buildWebUIApp are not in the task graph
# for :server:shadowJar, so they are automatically skipped.
# The version string uses the git commit count via runCatching{}.getOrDefault("0"),
# which returns "0" when .git is absent from the build context.  The mv normalises
# the name so the runtime stage COPY is always stable.
RUN ./gradlew :server:shadowJar --no-daemon \
    && mv server/build/Suwayomi-Server-*.jar server/build/server.jar

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user with a predictable UID/GID (matches the typical first user on
# Linux hosts, which simplifies bind-mount permission management).
RUN addgroup -S -g 1000 suwayomi \
    && adduser  -S -u 1000 -G suwayomi suwayomi \
    && mkdir -p /data \
    && chown suwayomi:suwayomi /data

COPY --from=server-build /build/server/build/server.jar /app/server.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER suwayomi
WORKDIR /app

EXPOSE 4567
VOLUME ["/data"]

ENTRYPOINT ["/app/entrypoint.sh"]
