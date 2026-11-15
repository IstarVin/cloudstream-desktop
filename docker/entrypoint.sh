#!/bin/sh
# Seeds the config on the first start (the volume starts empty), then runs the server.
set -e
conf="$XDG_CONFIG_HOME/cloudstream-desktop/config.toml"
if [ ! -f "$conf" ]; then
    mkdir -p "$(dirname "$conf")"
    cp /app/default-config.toml "$conf"
fi
# JAVA_OPTS replaces the default heap limit (e.g. JAVA_OPTS="-Xmx2g").
# shellcheck disable=SC2086 # JAVA_OPTS is a list of options
exec java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:--Xmx1g} -XX:G1PeriodicGCInterval=30000 -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -jar /app/cloudstream-desktop.jar "$@"
