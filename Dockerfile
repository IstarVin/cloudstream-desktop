# syntax=docker/dockerfile:1.7
# Headless server image: no mpv (play from the Android app or another client), Chromium for plugins that need a real browser.
#   docker build -t cloudstream-desktop .
#   docker run --init -p 6969:6969 -v cs-config:/config -v cs-data:/data --shm-size=1g cloudstream-desktop

# --- Build: the web UI (Vite+ via pnpm) and the server fat jar --------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
# Node for the web build (the Gradle build calls `vp`, which comes from the web package's own dependencies).
COPY --from=node:26-slim /usr/local/ /usr/local/
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
RUN npm install -g pnpm@12.16.1

WORKDIR /src
COPY . .
RUN cd web && pnpm install --frozen-lockfile
ENV PATH="/src/web/node_modules/.bin:${PATH}"
# No Android SDK here, so settings.gradle.kts leaves the :android module out.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :server:shadowJar -x test \
    && cp server/build/libs/cloudstream-desktop.jar /cloudstream-desktop.jar

# --- Runtime ----------------------------------------------------------------------------------------------------------
# Debian, not Ubuntu: Ubuntu's chromium package is a snap stub that doesn't run in a container.
FROM debian:bookworm-slim

LABEL org.opencontainers.image.title="cloudstream-desktop" \
      org.opencontainers.image.description="Headless Cloudstream server with web UI" \
      org.opencontainers.image.source="https://github.com/IstarVin/cloudstream-desktop"

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless chromium xvfb fonts-liberation ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Chromium refuses to start as root or inside Docker's default seccomp profile with its own sandbox; the container is
# the sandbox. The Debian wrapper script appends CHROMIUM_FLAGS from /etc/chromium.d to every launch.
RUN printf '%s\n' 'export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-dev-shm-usage --disable-gpu"' > /etc/chromium.d/container

RUN useradd --uid 1000 --create-home app \
    && mkdir -p /config /data \
    && chown app:app /config /data

COPY --from=build /cloudstream-desktop.jar /app/cloudstream-desktop.jar
COPY docker/config.toml /app/default-config.toml
COPY docker/entrypoint.sh /app/entrypoint.sh

USER app
# config.toml lands in /config/cloudstream-desktop/, the database, plugin jars and Chromium profile in /data/cloudstream-desktop/.
ENV XDG_CONFIG_HOME=/config XDG_DATA_HOME=/data
VOLUME ["/config", "/data"]
EXPOSE 6969
# /api/auth/status is reachable without a pairing token.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:6969/api/auth/status >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
