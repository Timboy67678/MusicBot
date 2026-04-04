# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Cache dependencies before copying source so re-builds are faster
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends libopus0 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Layer 1 — runtime dependencies (~80 MB, cached by Docker unless pom.xml changes)
COPY --from=build /build/target/libs ./libs

# Layer 2 — application jar (~100 KB, the only layer that changes on code edits)
COPY --from=build /build/target/JMusicBot.jar .

# Data written at runtime:
#   config.txt       - bot configuration (token, owner, etc.)
#   serversettings.json - per-guild settings
#   Playlists/       - saved playlists
# Mount these via a volume so they persist across container restarts.
VOLUME ["/app/data"]

# The bot reads config.txt from the working directory by default.
# We symlink the data directory entries into /app so the bot finds them
# without any code changes.
ENTRYPOINT ["/bin/sh", "-c", "\
  ln -sf /app/data/config.txt /app/config.txt 2>/dev/null || true; \
  ln -sf /app/data/serversettings.json /app/serversettings.json 2>/dev/null || true; \
  ln -sf /app/data/Playlists /app/Playlists 2>/dev/null || true; \
  exec java \
    -Dnogui=true \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=5 \
    -jar /app/JMusicBot.jar \
"]
