# syntax=docker/dockerfile:1
# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Cache dependencies before copying source so re-builds are faster.
# The cache mount persists the local repo across builds even if this layer's
# own Docker cache gets invalidated, so dependencies aren't re-downloaded.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends libopus0 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/libs ./libs
COPY --from=build /build/target/JMusicBot.jar .

ENTRYPOINT ["/bin/sh", "-c", "\
  ln -sf /app/data/config.txt /app/config.txt 2>/dev/null || true; \
  ln -sf /app/data/serversettings.json /app/serversettings.json 2>/dev/null || true; \
  ln -sf /app/data/Playlists /app/Playlists 2>/dev/null || true; \
  exec java \
    -Dnogui=true \
    -XX:MaxGCPauseMillis=200 \
    -jar /app/JMusicBot.jar \
"]
