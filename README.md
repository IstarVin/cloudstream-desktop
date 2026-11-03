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

### Docker

A headless image (no mpv; play from the Android app or another client) with Chromium for plugins that need a browser.
It's published to GHCR by `.github/workflows/docker.yml` on pushes to `main` and `v*` tags (amd64 only).

```bash
docker run -d --init --name cloudstream -p 6969:6969 --shm-size=1g \
  -v cs-config:/config -v cs-data:/data ghcr.io/istarvin/cloudstream-desktop:latest
# or build it yourself: docker compose up -d --build
```

Settings live in `/config/cloudstream-desktop/config.toml` (seeded from `docker/config.toml`; restart after editing).
Requests from outside the container need the pairing token: read it with
`docker exec cloudstream cat /data/cloudstream-desktop/token` and open `http://<host>:6969/pair?token=<token>`.
Don't expose port 6969 to the internet.

Source priority in Settings and the Sources sheet provides seven named profiles for source and quality scores,
with optional filters for failed or negative-priority links. Auto-play waits for links to resolve and starts as soon as one reaches a combined priority of 10; the rest keep loading in the background. If none does, it plays the best link once they're all in.
See [source profiles](docs/SOURCE_PROFILES.md) for behavior and settings API details.

## Install the web app

The production web UI is an installable PWA that opens in its own window with a Cloudstream launcher icon.
Build and serve it normally (`./gradlew :server:run`), then open it in your browser:

- **Desktop Chrome / Edge:** use the install icon in the address bar or the browser menu's install option.
- **Android Chrome:** open the browser menu and choose **Install app** / **Add to Home screen**.
- **iPhone / iPad Safari:** use **Share → Add to Home Screen**, with **Open as Web App** enabled when offered.

Installation and the service worker require a secure origin: `http://localhost:6969` or
`http://127.0.0.1:6969` works on the server's own computer. On another device, serve Cloudstream over HTTPS
with a trusted certificate (for example, through a reverse proxy); a plain HTTP LAN address does not qualify.
The manifest and service worker are included in production builds, so use a built app to test installation.

The server must remain running and reachable for browsing and playback. The service worker caches only a
small connection-help page, not library data, pairing tokens or streams. If the server is unavailable when
opening the app, that page offers a retry. New service worker versions activate after existing app windows
and tabs close, so updates do not interrupt playback.

## Android app

`android/` is a phone, tablet and Android TV client that plays locally with ExoPlayer instead of controlling mpv: the same home,
search, title, library and settings pages, plus libass subtitles, a source picker and touch gestures on phones. Plugins still run
on the desktop, so the server must be running and reachable on the LAN.

```bash
./gradlew :android:assembleDebug   # android/build/outputs/apk/debug/android-debug.apk; needs ANDROID_HOME or local.properties
```

Like the web UI, the app joins casting while it's open: the cast button sends Play to the desktop's mpv, a browser
tab or another phone/TV, with a now-playing bar and remote; other devices can cast to it (Settings → Playback →
Casting); and the player's cast button continues the episode on another device. It also shows the desktop's
Cloudflare Solve banner, and has the Browser settings and dev-mode Rebuild.

Open the app and scan the code from Settings → Pair a phone on the desktop, or enter the address and token by hand. The
module is only part of the build when an Android SDK is found, so server-only checkouts build as before.

On Android TV, launch Cloudstream from the TV launcher and enter the desktop address and token shown under the
pairing code. TV mode uses a navigation rail, larger text and posters, focus outlines, and pickers that contain
remote focus. Use the D-pad to move and Select to activate. During playback, Select/Up/Down reveals hidden controls,
Left/Right seeks when controls are hidden, and the focused timeline seeks by the configured seek step. Media keys
support play/pause, rewind/fast-forward and previous/next episode. Back closes a picker, hides playback controls,
then returns to the title.

TV UI tests run with television configuration on either a phone emulator or a TV image. Select a disposable emulator
so tests do not run on your personal device:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew :android:connectedDebugAndroidTest
```

## Develop

| What | Command |
| --- | --- |
| Server tests | `./gradlew :shared:test :server:test` (the Chromium tests skip when it isn't installed) |
| Android build and lint | `./gradlew :android:assembleDebug :android:lintDebug` |
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
- `shared/`: API shapes and playback rules (ranking, source profiles, auto-play, subtitles, skips) shared by the server
  and the Android app.
- `android/`: the Android app (Compose, Media3 ExoPlayer, libass).
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
