#!/usr/bin/env bash
# Builds cloudstream-desktop and installs it for the current user:
#   ~/.local/share/cloudstream-desktop/cloudstream-desktop.jar   the server (web UI bundled)
#   ~/.config/systemd/user/cloudstream-desktop.service          starts with the graphical session
#   ~/.local/share/applications/cloudstream-desktop.desktop     opens the UI
#   ~/.config/mpv/script-opts/cloudstream.conf                  keybindings (only if missing)
# Re-run it to update. `install.sh --uninstall` removes everything except your data and config.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
config="${XDG_CONFIG_HOME:-$HOME/.config}"
dest="$data/cloudstream-desktop"
unit="$config/systemd/user/cloudstream-desktop.service"

if [[ "${1:-}" == "--uninstall" ]]; then
    systemctl --user disable --now cloudstream-desktop.service 2>/dev/null || true
    rm -f "$unit" "$data/applications/cloudstream-desktop.desktop" \
        "$data/icons/hicolor/scalable/apps/cloudstream-desktop.svg" "$dest/cloudstream-desktop.jar"
    systemctl --user daemon-reload
    echo "Uninstalled. Data and settings remain in $dest and $config/cloudstream-desktop."
    exit 0
fi

for cmd in java mpv vp; do
    command -v "$cmd" >/dev/null || { echo "error: $cmd is not installed" >&2; exit 1; }
done

echo "Building…"
(cd "$repo" && ./gradlew --console=plain -q :server:shadowJar)

install -Dm644 "$repo/server/build/libs/cloudstream-desktop.jar" "$dest/cloudstream-desktop.jar"
install -Dm644 "$repo/packaging/cloudstream-desktop.service" "$unit"
install -Dm644 "$repo/packaging/cloudstream-desktop.desktop" "$data/applications/cloudstream-desktop.desktop"
install -Dm644 "$repo/packaging/cloudstream-desktop.svg" "$data/icons/hicolor/scalable/apps/cloudstream-desktop.svg"
if [[ ! -e "$config/mpv/script-opts/cloudstream.conf" ]]; then
    install -Dm644 "$repo/mpv/cloudstream.conf" "$config/mpv/script-opts/cloudstream.conf"
fi
command -v update-desktop-database >/dev/null && update-desktop-database "$data/applications" || true

# Make the session's display variables visible to user services (most desktops do this already).
systemctl --user import-environment DISPLAY WAYLAND_DISPLAY XAUTHORITY XDG_SESSION_TYPE 2>/dev/null || true
systemctl --user daemon-reload
systemctl --user enable cloudstream-desktop.service
systemctl --user restart cloudstream-desktop.service

echo "Installed. The UI is at http://127.0.0.1:6969 (first start builds the plugins, which takes a minute)."
echo "Logs: journalctl --user -u cloudstream-desktop -f"
