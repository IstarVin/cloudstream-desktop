# Cloudstream Desktop

A local server that runs the Cloudstream plugins from the extensions repo on the desktop JVM, serves a web UI
(desktop and phone), and plays streams in mpv. See [docs/PLAN.md](docs/PLAN.md) for the design.

## Run it

```bash
./gradlew :server:run            # builds the web UI and the plugins, then serves http://127.0.0.1:6969
packaging/install.sh             # or install as a systemd --user service that starts with your session
```

Needs a JDK (17+), mpv (0.39+), Vite+ (`vp`), and the extensions repo with its Android SDK (`local.properties`).
Chromium (or Google Chrome) is optional: plugins behind Cloudflare or that need a real browser use it, started on
demand. Pick headless, Xvfb or a visible window on the Settings page; checks it can't pass show a Solve banner.
The first start writes `~/.config/cloudstream-desktop/config.toml`; edit `plugins.dev_path` if the extensions repo
lives elsewhere.

## Develop

| What | Command |
| --- | --- |
| Server tests | `./gradlew :server:test` (the Chromium tests skip when it isn't installed) |
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
- `compat/webview-cdp/`: `WebViewResolver`, `CloudflareKiller` and `CookieManager` on a shared Chromium over CDP.
  It must come before `library-jvm` on the classpath, because the library has a `WebViewResolver` of its own.
- `web/`: SvelteKit SPA on Vite+, built into the server's resources.
- `mpv/`: `cloudstream.lua` and its default keybindings.
- `packaging/`: systemd unit, `.desktop` entry, installer.
- `.github/workflows/ci.yml`: web checks and server tests.
