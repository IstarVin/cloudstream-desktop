# syntax=docker/dockerfile:1.7
# Headless server image: no mpv (play from the Android app or another client), and a real browser for plugins behind
# Cloudflare or other bot checks.
#   docker build -t cloudstream-desktop .
#   docker run -p 6969:6969 -v cs-config:/config -v cs-data:/data -e TZ=Asia/Manila cloudstream-desktop
#
# Build args:
#   BROWSER=chrome (default)  Google Chrome stable on amd64; it presents as the most common browser (Chrome brand in
#                             client hints, Widevine, proprietary codecs). Other architectures always get Chromium.
#   BROWSER=chromium          Debian's Chromium.
#   BROWSER_REFRESH=<value>   Any new value reinstalls the browser instead of reusing a cached layer. CI passes the
#                             week, so published images never carry an outdated browser version (itself a bot signal).

# --- Build: the web UI (Vite+ via pnpm) and the server fat jar --------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
# Node for the web build (the Gradle build calls `vp`, which comes from the web package's own dependencies).
COPY --from=node:26-slim /usr/local/ /usr/local/
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
RUN npm install -g pnpm@12.16.1

WORKDIR /src
# Dependencies first, so source changes don't reinstall them.
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml web/.npmrc web/
COPY web/patches web/patches
RUN cd web && pnpm install --frozen-lockfile
ENV PATH="/src/web/node_modules/.bin:${PATH}"
COPY . .
# No Android SDK here, so settings.gradle.kts leaves the :android module out.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :server:shadowJar -x test \
    && cp server/build/libs/cloudstream-desktop.jar /cloudstream-desktop.jar

# --- Runtime ----------------------------------------------------------------------------------------------------------
# Debian, not Ubuntu: Ubuntu's chromium package is a snap stub that doesn't run in a container.
FROM debian:trixie-slim

LABEL org.opencontainers.image.title="cloudstream-desktop" \
      org.opencontainers.image.description="Headless Cloudstream server with web UI" \
      org.opencontainers.image.source="https://github.com/IstarVin/cloudstream-desktop"

COPY --from=eclipse-temurin:17-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}"

ARG TARGETARCH
ARG BROWSER=chrome
ARG BROWSER_REFRESH=
# tini reaps the browser's child processes, so `--init` isn't needed. tzdata so TZ works for every process. The fonts
# are what a desktop Linux install has; a browser with one font family and no emoji stands out to fingerprinting.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl tini tzdata xvfb \
        fonts-liberation fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji; \
    if [ "$BROWSER" = chrome ] && [ "$TARGETARCH" = amd64 ]; then \
        curl -fsSLo /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb; \
        apt-get install -y --no-install-recommends /tmp/chrome.deb; \
        rm /tmp/chrome.deb; \
        /opt/google/chrome/chrome --version; \
    else \
        apt-get install -y --no-install-recommends chromium; \
        /usr/lib/chromium/chromium --version; \
    fi; \
    rm -rf /var/lib/apt/lists/*

COPY docker/browser.sh /usr/local/bin/chromium

RUN useradd --uid 1000 --create-home app \
    && mkdir -p /config /data \
    && chown app:app /config /data

COPY --from=build /cloudstream-desktop.jar /app/cloudstream-desktop.jar
COPY docker/config.toml /app/default-config.toml
COPY docker/entrypoint.sh /app/entrypoint.sh

USER app
# config.toml lands in /config/cloudstream-desktop/, the database, plugin jars and Chromium profile in /data/cloudstream-desktop/.
# C.UTF-8 keeps the JVM on UTF-8 (en_US.UTF-8 isn't generated in this image); LANGUAGE sets the browser's language and
# Accept-Language. Set TZ (and LANGUAGE if you like) to match where your IP is: a UTC browser on a non-UTC IP fails
# Cloudflare's checks.
ENV XDG_CONFIG_HOME=/config XDG_DATA_HOME=/data \
    LANG=C.UTF-8 LANGUAGE=en_US:en
VOLUME ["/config", "/data"]
EXPOSE 6969
# /api/auth/status is public; all app data requires sign-in, including on localhost.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:6969/api/auth/status >/dev/null || exit 1
# -s: tini still reaps when it isn't PID 1 (when the container is also started with --init).
ENTRYPOINT ["/usr/bin/tini", "-s", "--", "/app/entrypoint.sh"]
