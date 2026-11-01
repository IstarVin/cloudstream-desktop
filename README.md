# Cloudstream Desktop

A local server that runs the Cloudstream plugins from the extensions repo on the desktop JVM, serves a web UI
(desktop and phone), and plays streams in mpv. See [docs/PLAN.md](docs/PLAN.md) for the design.

## Run it

```bash
./gradlew :server:run            # builds the web UI and the plugins, then serves http://127.0.0.1:6969
packaging/install.sh             # or install as a systemd --user service that starts with your session
```

Needs a JDK (17+), mpv (0.39+), Vite+ (`vp`), and the extensions repo with its Android SDK (`local.properties`).
The first start writes `~/.config/cloudstream-desktop/config.toml`; edit `plugins.dev_path` if the extensions repo
lives elsewhere.

## Develop

| What | Command |
| --- | --- |
| Server tests | `./gradlew :server:test` |
| Phase 0 check from the CLI | `./gradlew :server:run --args="spike Dulo dune"` |
| Web UI with hot reload | `cd web && vp dev` (proxies `/api` to a running server) |
| Web checks and tests | `cd web && vp check && vpr check && vp test` |
| Exercise link fallback | run the server with `-Dcs.debug.failSources="Dulo Pickle,…"` |

Plugins hot-reload whenever `<Module>/build/<Module>.jar` changes in the extensions repo. Settings → Plugins →
Rebuild runs `gradlew -I desktop-jars.init.gradle :Name:compilePluginJar`, which also produces jars for modules that
aren't marked `isCrossPlatform`.

## Layout

- `server/`: Ktor app (plugin host, API, mpv manager, HLS proxy, SQLite).
- `compat/android-shim/`: `Context`, JSON-backed `SharedPreferences`, `Log`, `Base64`, `Plugin`, `CloudStreamApp`,
  `DataStore`.
- `compat/webview-cdp/`: `CloudflareKiller` placeholder (Chromium/CDP in v1.1).
- `web/`: SvelteKit SPA on Vite+, built into the server's resources.
- `mpv/`: `cloudstream.lua` and its default keybindings.
- `packaging/`: systemd unit, `.desktop` entry, installer.
