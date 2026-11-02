#!/bin/sh
# Seeds the config on the first start (the volume starts empty), then runs the server.
set -e
conf="$XDG_CONFIG_HOME/cloudstream-desktop/config.toml"
if [ ! -f "$conf" ]; then
    mkdir -p "$(dirname "$conf")"
    cp /app/default-config.toml "$conf"
fi
exec java --enable-native-access=ALL-UNNAMED -jar /app/cloudstream-desktop.jar "$@"
