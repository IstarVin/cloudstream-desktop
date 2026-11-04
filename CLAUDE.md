# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A local Ktor server that runs Cloudstream plugins on the desktop JVM, serves a SvelteKit web UI, and plays streams in mpv. An Android/TV client (`android/`) plays locally with ExoPlayer but still depends on the desktop server, because plugins run only there. See [README.md](README.md) for user-facing setup, [docs/PLAN.md](docs/PLAN.md) for design history and findings, and [DESIGN.md](DESIGN.md) for the design system (the web UI is the visual reference; Android mirrors it).

## Commands

```bash
./gradlew :server:run                          # builds the web UI, loads plugins, serves http://127.0.0.1:6969
./gradlew :server:run --args="spike Dulo dune" # CLI check of one plugin (search + links), no web UI
./gradlew :shared:test :server:test            # server tests (Chromium tests skip if Chromium isn't installed)
./gradlew :server:test --tests 'desktop.StoreTest'   # single Kotlin test class (add .method for one test)
./gradlew :android:assembleDebug :android:lintDebug  # needs ANDROID_HOME or local.properties sdk.dir
./gradlew :android:testDebugUnitTest           # Android unit tests
cd web && vp dev                               # web UI with hot reload; proxies /api, /proxy, /pair to a running server
cd web && vp check && vpr check && vp test     # web lint/format, svelte-check, tests (what CI runs)
cd web && vp test run src/lib/ranking.test.ts  # single web test file
python3 -m unittest discover -s scripts -p 'test_*.py' -v   # release tooling tests
```

- The web toolchain is **Vite+** (`vp`/`vpr`, from the local `vite-plus` package), with pnpm. Import from `vite-plus`, not `vite` (a lint rule enforces it). Svelte runs in forced runes mode.
- `./gradlew :server:processResources` runs `vp install` + `vp build` (the `webBuild` task), which writes the SPA into `server/src/main/resources/web/` (gitignored). `vp` must be on PATH for any server build that processes resources.
- `:android` is included in `settings.gradle.kts` only when an Android SDK is found, so server-only checkouts build without it.
- Debug flags for the server JVM: `-Dcs.debug.failSources="Dulo Pickle,…"` (force source failures to exercise fallback), `-Dcs.debug.webview` (trace emulated WebView CDP events).
- Releases (Android `android-v*` tags, server `server-v*` tags, independent versions, notes in `release-notes/`) go through the `cloudstream-release` skill / `scripts/release.py`; don't tag by hand.

## Architecture

Modules (Gradle): `shared`, `compat:android-shim`, `compat:webview-cdp`, `server`, `android`; plus `web/` (pnpm, not Gradle-managed except through `server`'s `webBuild`).

**Plugin runtime** (`server/.../plugins/`). Cloudstream plugins are Android code; the server loads them on a plain JVM against `library-jvm`, so the compat modules stand in for Android APIs:
- `compat/android-shim` provides `Context`, JSON-backed `SharedPreferences`, `Log`, `Uri`, `DataStore`, `Plugin`, `Dispatchers.Main` and empty fragment classes (needed only so the JVM verifier can load converted plugins' settings sheets). `AndroidStubs` also layers the SDK's `android.jar` between the host and plugin class loaders (parent-first, so shims win).
- `compat/webview-cdp` supplies `WebViewResolver`, `CloudflareKiller`, `CookieManager` and a slice of `android.webkit.WebView` on one shared lazily-started Chromium over CDP. **It must come before `library-jvm` on the classpath** because the library ships its own `WebViewResolver`; a startup check and `WebViewResolverShadowTest` enforce this.
- Plugin sources (`PluginSource`): *dev mode* (`plugins.dev_path`, a local extensions repo built by Gradle with `server/src/main/resources/gradle/desktop-jars.init.gradle`, hot-reloaded when jars change) or *repo mode* (`RepoSource`: reads `repo.json`/`plugins.json`, hash-verifies downloads, keeps two versions for rollback; non-cross-platform `.cs3` plugins are converted dex→jar by `Cs3Converter`/dex2jar). `LinkageCheck` verifies each plugin's constant-pool references against the host runtime at load. Source/repo settings may only be changed from loopback (a paired device must not be able to point the server at an arbitrary repo).
- Dependency versions the plugins expect (OkHttp via NiceHttp, Jackson, jsoup, Rhino…) are provided by `server`'s classpath, because plugin jars don't bundle their `implementation` deps.

**Server** (`server/.../`). `Main.kt` wires config (`config/`, TOML at `~/.config/cloudstream-desktop/config.toml`), SQLite (`db/`), settings, `PluginHost`, then `Server.kt` (Ktor CIO + websockets). `api/Routes.kt` and `Catalog.kt` expose the REST/SSE API; `links/` resolves and proxies streams (`LinkService`, `StreamProxy` for HLS/DASH rewriting, ClearKey/CENC handling in `Cenc.kt`, MKV subtitle extraction); `player/` drives mpv over its IPC socket (`MpvManager`, `MpvIpc`, `Playback` for auto-play/fallback/prefetch, `SkipTimes` for AniSkip); `cast/DeviceHub` coordinates casting between browser tabs, the mpv player and Android clients; `auth/` does LAN pairing tokens (loopback is trusted, other clients need the token).

**`shared/`** holds the API DTOs and playback rules (link ranking, source profiles, auto-play, subtitles, skip) used by both the server and the Android app. The web UI cannot import Kotlin, so `web/src/lib/ranking.ts` (and types in `web/src/lib/types.ts`) reimplement the same logic; `testdata/*.json` cases are run by the Kotlin tests *and* the web tests to keep them in step. When changing ranking or source-priority behavior, change both implementations and update the shared cases.

**Web** (`web/`). SvelteKit static SPA (`adapter-static`, fallback `index.html`) served by Ktor at `/`; also an installable PWA (`src/service-worker`). Client state lives in `*.svelte.ts` stores in `src/lib/` (player, cast, search, sources); data fetching uses TanStack Query against `/api`.

**Android** (`android/`). Compose + Media3 ExoPlayer + libass; talks to the same `/api` and casting protocol as the web client (`data/Api.kt`, `data/Cast.kt`). Has TV mode (D-pad/focus handling in `ui/Tv.kt`, `player/TvRemote.kt`); instrumented TV tests run with `ANDROID_SERIAL=<disposable emulator> ./gradlew :android:connectedDebugAndroidTest`.

**mpv** (`mpv/cloudstream.lua`, `cloudstream.conf`) is bundled into the server jar and connects the player back to the server (OSD, pickers, skip prompts, episode switching).

## Docker

`Dockerfile` builds a headless image (no mpv, Chromium for browser-gated plugins); config seeds from `docker/config.toml`. `.github/workflows/docker.yml` publishes on `main` pushes and `server-v*` tags.
