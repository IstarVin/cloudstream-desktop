# Cloudstream Desktop

A local server that runs Cloudstream plugins (from a published repo, or built from a local extensions repo) on the
desktop JVM, serves a web UI
(desktop and phone), and plays streams in mpv. See [docs/PLAN.md](docs/PLAN.md) for the design.

## Run it

```bash
./gradlew :server:run            # builds the web UI, loads the plugins, then serves http://127.0.0.1:6969
packaging/install.sh             # or install as a systemd --user service that starts with your session
```

Needs a JDK (17+), mpv (0.39+) and Vite+ (`vp`). Plugins come from one of two sources (Settings → Plugins, desktop
only):

- **Online repo** (the default without a local checkout): `plugins.repo_url`, a Cloudstream `repo.json` or
  `plugins.json`. Plugins it publishes as cross-platform jars load as they are; the rest are converted from their
  `.cs3` with dex2jar. Downloads are checked against the repo's hashes, and Check for updates / Update / Roll back
  live on the Settings page.
- **Local extensions repo** (dev mode): `plugins.dev_path`, built with gradle and hot-reloaded. Needs the repo's
  Android SDK (`local.properties`).

Chromium (or Google Chrome) is optional: plugins behind Cloudflare or that need a real browser use it, started on
demand. Pick headless, Xvfb or a visible window on the Settings page; checks it can't pass show a Solve banner.
The first start writes `~/.config/cloudstream-desktop/config.toml`; set `plugins.source`, `plugins.repo_url` or
`plugins.dev_path` there.

## Develop

| What | Command |
| --- | --- |
| Server tests | `./gradlew :server:test` (the Chromium tests skip when it isn't installed) |
| Phase 0 check from the CLI | `./gradlew :server:run --args="spike Dulo dune"` |
| Web UI with hot reload | `cd web && vp dev` (proxies `/api` to a running server) |
| Web checks and tests | `cd web && vp check && vpr check && vp test` |
| Exercise link fallback | run the server with `-Dcs.debug.failSources="Dulo Pickle,…"` |
| Trace emulated WebViews | run the server with `-Dcs.debug.webview` (every CDP event and request decision, on stderr) |

In dev mode, plugins hot-reload whenever `<Module>/build/<Module>.jar` changes in the extensions repo. Settings → Plugins →
Rebuild runs `gradlew -I desktop-jars.init.gradle :Name:compilePluginJar`, which also produces jars for modules that
aren't marked `isCrossPlatform`.

## Layout

- `server/`: Ktor app (plugin host, API, mpv manager, HLS proxy, SQLite).
- `compat/android-shim/`: `Context`, JSON-backed `SharedPreferences`, `Log`, `Base64`, `Uri`, `Dispatchers.Main`,
  `Plugin`, `CloudStreamApp`, `DataStore`, and empty fragment classes the verifier needs for converted plugins'
  settings sheets.
- `compat/webview-cdp/`: `WebViewResolver`, `CloudflareKiller`, `CookieManager` and the slice of `android.webkit.WebView`
  StreamCorner uses, on a shared Chromium over CDP. It must come before `library-jvm` on the classpath, because the
  library has a `WebViewResolver` of its own.
- `web/`: SvelteKit SPA on Vite+, built into the server's resources.
- `mpv/`: `cloudstream.lua` (OSD, pickers, AniSkip chapters and Skip prompt, uosc menus and buttons when uosc is
  installed) and its default keybindings.
- `testdata/`: cases shared by the server and web tests (link ranking).
- `packaging/`: systemd unit, `.desktop` entry, installer.
- `.github/workflows/ci.yml`: web checks and server tests.
