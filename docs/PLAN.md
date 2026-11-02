# Cloudstream Desktop: plan

A local server with a web UI that browses, searches and resolves streams the way the Cloudstream app does. It uses the
plugins from `/mnt/AJ/Projects/cloudstream-extensions-istarvin`, then plays the chosen stream in mpv. An mpv Lua script
connects the player back to the server, so episode switching, source fallback and progress tracking work from either
the browser or the player.

_Last updated: 2026-11-01_

---

## Status (2026-11-01)

Phases 0–9 are implemented (v1, v1.1 and v1.2). Each phase's check was run against live providers, with mpv on a null
video/audio output. Phase 10 (v1.3) is implemented; its StreamCorner check is still open (below).

**Phase 0 findings**

- Runtime artifact: `com.github.recloudstream.cloudstream:library-jvm:94e955836b` (JitPack). It matches the
  `cloudstream3:pre-release` stub fetched on 2026-10-14; the library's sources didn't change until `dc9d1f0f` on
  2026-10-21. It pulls Kotlin 2.4.0, coroutines 1.11, NiceHttp 0.4.18 (OkHttp 5.3.2), Jackson 2.13.1 and Rhino 1.8.1.
- The JVM target has `WebViewResolver` (same signatures as the app's) but no `CloudflareKiller` or `DdosGuardKiller`.
- `newExtractorLink` works on the JVM as is. The "app's crypto library" note in the extensions repo doesn't apply,
  because `library-jvm` brings `cryptography-provider-optimal`.
- Dulo and OmniStream return links, and they play in mpv with their headers. All 36 enabled plugins load, tier B
  included.

**Where the implementation differs from the plan**

- *No changes to the extensions repo (§6).* The server builds jars with a Gradle init script
  (`server/src/main/resources/gradle/desktop-jars.init.gradle`). It forces `isCrossPlatform` and skips the jdeps
  check, so tier A′ and B plugins get jars without version bumps or a publish. The §6 seams are still worth doing
  upstream, but v1 no longer depends on them.
- *android.jar as a verifier layer.* Plugins that subclass `Plugin` build their settings dialogs in lambdas whose
  bodies live in the plugin class. The JVM verifier therefore loads `Activity`, `EditText` and similar classes even
  though those dialogs never run. The server puts the SDK's `android.jar` stubs in a class loader between the host
  and the plugins. Delegation is parent-first, so the working shims win.
- *Plugin settings* use a generic key/value form. Keys appear once a plugin has read them (the shim records reads
  and their defaults), or they can be added by hand.
- *Extra setting* `playback.proxy_all` sends every stream through the proxy, for sites that reject mpv's direct
  requests.
- *Per-host limit (§9)* is OkHttp's own `maxRequestsPerHost` on the plugins' shared dispatcher
  (`network.max_requests_per_host`, default 2), not a semaphore: waiting calls queue in OkHttp instead of holding
  threads. The desktop `CloudflareKiller` never sends a nested request from inside its interceptor, so the
  deadlock the extensions repo warns about can't occur.

**Phase 7 results (v1 hardening)**

- Per-host limit: `HostLimitTest` makes 8 parallel `app.get` calls (some through derived clients) and sees at most
  2 at the upstream.
- WebView failures: the library's `WebViewResolver` is shadowed (Phase 8), so tier C sources fail with ordinary
  exceptions that plugins already catch per source. Search, home, load and links map failures to a short reason
  (`describe`): Chromium missing, timeout, `NotImplementedError`, linkage.
- *TonkaCine `AEADBadTagException`:* not a JVM problem. Vidnest's payloads fail to decrypt in Python too, so the site
  changed its secret (`VIDNEST_SECRET` in `shared/tonkacinesource/TonkaCineDecoder.kt`). It fails on Android as well;
  it needs a fix in the extensions repo. Vidrock still works.
- Linkage report: `LinkageCheck` reads each plugin's constant pool at load and resolves every reference into the
  host runtime (library, Kotlin, OkHttp, Jackson, jsoup, Rhino…). Problems are logged and shown in amber on the
  plugins page, together with the first `LinkageError` a call actually hits. All 36 plugins currently link cleanly.
- Tests: 30 server tests (store, auth over a real LAN socket, the proxy end to end, auto-play rule, linkage, the
  shadow's API parity, host limit, Chromium) and 12 web tests (SSE streams, player socket, player store).
- CI: `.github/workflows/ci.yml` runs the web checks and the server tests. It hasn't run yet: the repo has no remote.
- *Still open:* the real-session check. Chromium ran headed in the Wayland session, but a visible mpv window and
  QR pairing from a real phone still need checking by hand.

**Phase 8 results (v1.1, Chromium over CDP)**

- `compat/webview-cdp` ships `WebViewResolver` (the library's exact public API, checked by a test), a real
  `CloudflareKiller` and an `android.webkit.CookieManager` shim, all on one shared Chromium (`desktop.cdp.Chromium`)
  with a persistent profile, lazily started and stopped after `chromium.idle_minutes`. A startup check fails fast if
  the library's `WebViewResolver` wins the classpath.
- Cloudflare solves run once per host in the background and outlive the request that started them (OmniStream
  cancels slow sources after a few seconds). Clearances and Chromium's user agent are saved in
  `chromium/clearances.json` and shared by every `CloudflareKiller.savedCookies`.
- Checks the background browser can't pass are listed (banner on every page, `/api/browser`). "Solve" switches
  Chromium to a visible window; the clearance is picked up when it appears.
- Live results:
  - GoPlay: headless fails Turnstile. Headed passes on its own, and the session cookies reach GoPlay through the
    `CookieManager` shim. After a restart, headless mode reuses the saved session, and playback through the proxy
    reaches `playing`.
  - *Update, 1 November 2026:* headless now passes GoPlay's Turnstile on its own, in about 3 s from a fresh profile
    (Chromium 156). Two launch flags caused the failure, each enough on its own: `--headless` (Turnstile stalls on
    its checkbox) and `--remote-debugging-port=0`. Headless mode now runs the full browser with
    `--ozone-platform=headless` on a fixed loopback port. `LIVE_GOPLAY=1` runs the live check in `ChromiumTest`.
  - *GoPlay playback, 1 November 2026:* it still failed with "All links failed". Three proxy problems caused this,
    each fixed in `StreamProxy`. (1) GoPlay's master URL is about 4.5 KB; base64 in the proxy path pushed it past
    ffmpeg's 4096-byte URL limit, so mpv requested a truncated URL and got 403. Long URLs now get a short alias on
    their token. (2) Its SAMPLE-AES keys are inline `data:` URIs, which crashed the rewrite (NPE, 500) and which
    ffmpeg won't open anyway. The proxy now serves them. (3) The streams are fMP4 `cbcs`; ffmpeg's HLS demuxer loads
    the key but never gives it to the fMP4 demuxer, so the picture was gray. Playback now reads the key ahead and
    passes `seg_format_options=decryption_key=…`. Masters and VOD playlists are kept per token, because mpv fetches
    a master twice and GoPlay's playlist URLs work only once.
  - mkvbase.site (OmniStream): headless fails Cloudflare. "Solve" passes in a visible window in about 6 s, and after
    a restart the saved clearance works with no new solve.
  - MissAV isn't challenging plain requests at the moment.
  - *MkvDrama is broken upstream:* mkvdrama.net now answers with a JSON API that needs a key. Plain curl gets the
    same 401.
- *Untested:* `xvfb` mode (Xvfb isn't installed here).

**Phase 9 results (v1.2, repo mode)**

- *The gradle plugin already publishes desktop jars:* `plugins.json` carries `jarUrl`, `jarHash` (`sha256-…`) and
  `jarFileSize` for every `isCrossPlatform` module. That covers 20 of the 38 plugins.
- *The other 18 are converted from their `.cs3`* (Android dex) with dex2jar (`de.femtopedia.dex2jar:dex-translator`,
  `Cs3Converter`), so the §6 seams aren't needed for the desktop. They were also losing ground: OmniStream v7
  (`696169e`, 2026-11-01) turned `isCrossPlatform` off for an Android settings sheet. Conversion findings:
  - dex2jar "sanitizes" member names by default, which turns Kotlin's `Result.constructor-impl` into
    `constructor_impl`. `LinkageCheck` caught it; `dontSanitizeNames(true)` fixes it.
  - The output is class version 50 without stack map frames, so HotSpot verifies it the old way. That loads the
    superclasses of settings fragments (`BottomSheetDialogFragment` and its chain), so `android-shim` has empty open
    classes for them. dex2jar's `computeFrames` produces broken code (`java/util/Object`), so it stays off.
  - D8 drops `@CloudstreamPlugin`. The entry class comes from `manifest.json`'s `pluginClassName`, as in the app.
  - Converted jars record a converter version and are converted again when it goes up.
- `RepoSource` reads a `repo.json` (its `pluginLists`) or a `plugins.json` and checks every download against the
  repo's size and hash. It keeps the last two versions per plugin for rollback, and saves the index and the installs
  so plugins load offline. Each repo URL gets its own folder. New plugins install on their own; updates wait for the
  UI (Check for updates, Update, Update all, Roll back). The index is re-read every 6 hours.
- The source (`plugins.source`: dev or repo, default dev only when `plugins.dev_path` holds a checkout) and
  `plugins.repo_url` are settings that switch live. Only loopback may change them: a paired phone that could point the
  server at another repo could run any code on the desktop.
- Live results on a fresh install (no extensions repo, `ANDROID_HOME` unset, empty `user.home`): all 36 enabled
  plugins load from the published repo (20 jars, 16 converted), and all of them link cleanly. Converted plugins work
  end to end:
  - OmniStream v7 returns 23–33 links and hundreds of subtitles for "Dune".
  - KissKH and Vegamovies resolve links.
  - Kayoanime searches, and reads its settings (it asks for its Drive API key).
  - GoPlay reaches the desktop `WebViewResolver`; headless Turnstile fails it as in Phase 8.
- Against a local mirror of `plugins.json`, a version bump showed as an update, Update loaded it, and Roll back
  restored the previous jar, all through the API.
- Tests: 37 server tests (7 new: repo index, verified install, hash rejection, pruning, offline restart, `.cs3`
  conversion through the repo, reconversion after a converter bump, and the converter loading a real KissKH `.cs3`)
  and 12 web tests.

**Phase 10 results (v1.3)**

- Live, on an isolated server instance (dev mode, mpv on null outputs), with Frieren on OmniAnime:
  - *AniSkip:* E2 got an opening at 6–96 s and an ending at 1460–1550 s, matched to the 1560 s file. mpv's
    `chapter-list` held Opening/Episode/Ending/Episode. `PlayerState.skip` named the opening while inside it, and
    `POST /api/player/skip` jumped past it. E3 found its own ranges.
  - *Ids:* the first run found no ids at all. The library's sync-id keys are all `""` on the JVM, so `addMalId` and
    `addAniListId` overwrote each other; fixed by setting the app's keys at startup (`SyncIdTest`).
  - *Prefetch:* seeking past 85% took `next` from `waiting` to `resolving` (2, then 17 links) to `ready` (21 links)
    in about 4 s. Next then picked a link within a second, from the prefetched set.
  - *Ranking:* `/api/links` carries `order`, `score`, `successes` and `failures`. AnimoTv showed 1 success after
    E2's play.
- *Settings schema:* the four plugins report their fields over `/api/plugins` in dev mode. The freshly built `.cs3`s
  of OmniStream, Kayoanime, Extractors and JavHdz still carry the schema after dex2jar, and they link cleanly.
- *ClearKey:* checked against a local CENC-encrypted DASH stream. mpv with `demuxer-lavf-o=cenc_decryption_key`
  decodes it without warnings; without a key, or with a wrong one, it logs hundreds of decode errors.
- *uosc:* not installed here, so only the `mp.input.select` path ran. The uosc calls follow uosc's wiki
  (`open-menu`/`update-menu` keyed by `type`, `set-button`) but haven't run against a real uosc.
- *StreamCorner (open):* the emulation passes its tests on real Chromium (interceptor-served page on a real origin,
  JS bridge with return values, an out-of-process iframe, a module worker, blocked navigation, no leaked tabs). Live,
  the plugin loads and builds its bridge page, the page reports ready over the bridge, and the site's decoder worker
  loads. But the worker never answers the plugin's `provider-list` message, so home rows time out.
  - A plain CDP probe with no emulation at all (the same page, real origin, headless Chromium, with or without a GPU)
    behaves the same way, while the site's own page does fetch its data.
  - So it's the site's worker refusing this setup (fingerprint gating, or a protocol change since StreamCorner's
    2026-10-30 update), not the WebView layer. It needs a run in xvfb/headed mode, or a comparison with the phone.
  - StreamCorner stays in the default `plugins.disabled` list until then.
- Tests: 52 server tests (new: ranking parity, AniSkip parsing/cache/auto-skip, settings schema, sync ids, ClearKey
  keys, Android API parity, and the WebView shim on Chromium) and 23 web tests (new: ranking parity).
- Upstream (extensions repo, uncommitted): `SETTINGS_SCHEMA` on OmniStream, Kayoanime, Extractors and JavHdz, with
  version bumps (Extractors 22, Kayoanime 4, JavHdz 15; OmniStream was already at 9 in the working tree).

**Next:** StreamCorner's live check (above). The real-session check from Phase 7 is still open; a real uosc and the
phone remote's Skip button belong in it. Upstream, the Vidnest secret and MkvDrama's new site need plugin fixes, and
MkvDrama's settings get a schema once it works again.

## 1. Goals

1. **Same plugins, no rewrite.** Run the existing Kotlin plugins (about 26k lines across 37 modules plus `shared/`) as
   they are. A fix in the extensions repo should reach the desktop app without a second copy of the code.
2. **Web UI** for home pages, search, details, seasons and episodes, source picking, history and settings. It is served
   by the server, so it also works from a phone on the LAN as a remote.
3. **The server owns mpv.** It starts mpv, loads streams with the right headers, tracks progress and reacts to player
   events.
4. **An mpv script** that makes the player feel like part of the app: title on the OSD, next/previous episode, a source
   picker inside mpv, automatic fallback when a link dies, and progress reported back.

Out of scope for v1: downloads, DRM (Widevine) playback, multiple users, hosting it on a server that isn't the machine
mpv runs on, downloading plugins from the `builds` branch, and Chromium-backed plugins (tier C; that's v1.1).

---

## 1a. Decisions

| Topic | Decision |
| --- | --- |
| Where the UI is used | Desktop browser **and** phone on the LAN as a remote. LAN access requires a token; the layout is mobile-first. |
| NSFW providers | Loaded, but hidden behind a settings toggle that is **off** by default. It hides providers, search results, home rows and history entries. |
| Plugin source | v1: **local extensions repo only** (dev mode, built with Gradle, hot-reloaded). v1.2 adds **repo mode**: published jars, plus `.cs3` files converted with dex2jar. Chosen on the Settings page, desktop only. |
| Web UI | **Vite+** (`vp`) toolchain, **Svelte 5** + TypeScript. |
| v1 plugin scope | Tiers **A + A′ + B** (about 29 plugins). Tier C (Chromium/CDP) is **v1.1**. StreamCorner (D) comes later. Fetcharr is excluded. |
| mpv lifecycle | Started on the **first play**, then kept open and idle between items. Restarted on the next play if you close it. |
| Play behaviour | **Auto-play the best link** once a few have arrived or after a short timeout. The sources sheet stays open so you can switch. |
| Launch | **systemd `--user` service** that starts at login, plus a `.desktop` entry that opens the UI. |

---

## 2. Key decision: host the plugins on the JVM; don't port them

Rewriting the plugins in TypeScript, Python or anything else would create a fork that falls behind every time the
extensions repo changes. That repo is actively maintained, has parser tests, and publishes through CI. The plugins are
already most of the way to running on a desktop JVM:

- **21 modules already set `isCrossPlatform = true`.** For those, the Cloudstream gradle plugin already emits a plain
  JVM `.jar` next to the `.cs3`. Examples: `Dulo/build/Dulo.jar` and `OmniStream/build/OmniStream.jar`.
- **The Cloudstream core is Kotlin Multiplatform.** `recloudstream/cloudstream` ships a `library` module with a `jvm`
  target. It contains `MainAPI`, `ExtractorApi`, the load-response builders, `app` (NiceHttp), `loadExtractor` and
  the built-in extractors. The desktop host depends on that artifact, so the plugins call the real implementations
  instead of stubs.
- **The code already guards against Android.** For example, `shared/goplaysource/GoPlaySession.kt` reaches
  `CookieManager` by reflection, so OmniStream's cross-platform jar doesn't import Android classes.

So the server is a **Kotlin/JVM process** that loads plugin jars with one class loader per plugin, as the app does. It
provides the few Android-only pieces the plugins touch through a small desktop compatibility layer. The web UI is a
separate static frontend that the server serves.

> Phase 0 is a spike that proves this before anything else is built (see §8).

---

## 3. Architecture

```
┌──────────────────────┐   HTTP + WebSocket   ┌───────────────────────────────────────────────┐
│ Web UI (browser/LAN) │ ───────────────────► │ cs-server (Kotlin, Ktor, single JVM)          │
└──────────────────────┘                      │                                               │
                                              │  ┌─────────────┐  ┌─────────────────────────┐ │
                                              │  │ Plugin host │  │ Desktop compat layer    │ │
                                              │  │ (classload- │  │ Context/SharedPrefs,    │ │
                                              │  │  ers, APIs) │  │ WebViewResolver,        │ │
                                              │  └──────┬──────┘  │ CloudflareKiller → CDP  │ │
                                              │         │         └───────────┬─────────────┘ │
                                              │  ┌──────▼──────┐  ┌───────────▼─────────────┐ │
                                              │  │ Link service│  │ Headless Chromium (CDP) │ │
                                              │  │ + HLS proxy │  └─────────────────────────┘ │
                                              │  └──────┬──────┘                              │
                                              │  ┌──────▼──────┐   ┌──────────┐               │
                                              │  │ mpv manager │   │ SQLite   │               │
                                              │  └──────┬──────┘   └──────────┘               │
                                              └─────────┼─────────────────────────────────────┘
                                                        │ JSON IPC (unix socket)
                                              ┌─────────▼───────────────────────┐
                                              │ mpv  +  cloudstream.lua script  │
                                              └─────────────────────────────────┘
```

The mpv script and the server talk over **mpv's JSON IPC**, so the Lua script needs no HTTP client:

- Server → script: `script-message-to cloudstream <cmd> <json>`.
- Script → server: `mp.commandv("script-message", "cs-event", json)`. IPC clients receive this as a `client-message`
  event.

---

## 4. Components

### 4.1 Plugin host

- **Sources of plugins:**
  - **Dev mode (v1, the only mode):** point at the extensions repo and load `*/build/<Name>.jar`. A "Rebuild plugins"
    action in the UI and server startup run `./gradlew make` for the enabled modules. Watch those files and hot-reload
    a plugin when its jar changes, so `./gradlew :Name:make` in the extensions repo is enough during plugin work.
  - **Repo mode (v1.2):** read `plugins.json` (directly or through a `repo.json`) from the repo's `builds` branch.
    Download the cross-platform jars it lists (`jarUrl`/`jarHash`), and convert every other plugin's `.cs3` with
    dex2jar. Check each download against its hash, and keep the previous versions for rollback.
- **Loading:** one `URLClassLoader` per plugin, with the host's runtime as the parent. Find the entry class from the
  `.cs3` `manifest.json` (`pluginClassName`), or scan for `@CloudstreamPlugin`. Instantiate it and call `load()` /
  `load(context)`. `registerMainAPI` / `registerExtractorAPI` fill `APIHolder.allProviders` and `extractorApis`, the
  same lists `loadExtractor` uses. The **Extractors** plugin must load first because Javtiful, SexTB, MissAV, JavStream
  and others resolve hosts through it.
- **Runtime classpath = what the app provides.** Plugin jars don't bundle their `implementation` dependencies. The host
  must ship the same versions the root `build.gradle.kts` pins: NiceHttp 0.4.18, jsoup 1.22.2, Jackson **2.13.1**,
  Rhino **1.8.1**, coroutines, fuzzywuzzy, gson, kotlinx-serialization-json and bcpkix.
- **Per-plugin state:** enabled/disabled, NSFW flag (from `tvTypes`), load errors shown in the UI, and last-used time.

### 4.2 Desktop compatibility layer

What each plugin needs beyond the core library, based on a scan of the repo:

| Tier | Plugins | Needs |
| --- | --- | --- |
| **A: runs as-is** | AnimeFlix, AnimeX, AnimoTvSlash, AniPm, CnoTo, Dulo, Hanime, HentaiHaven, HentaiMama, Hentaverse, HStream, JavFc2, KatDrama, Miruro, MoviesMod, Movy, Reanime, SevenMovies, TonkaCine, ZLive | Core library only. JavFc2 and Miruro also use `loadExtractor`. |
| **A′: needs a jar** | FourKHDHub, GoPlay, Javtiful, KissKH, Vegamovies, XPrimeHub | No Android imports, but `isCrossPlatform` is unset. Set it to `true` in the extensions repo. GoPlay and the `nexdrive` users also need tier C. |
| **B: Context / prefs** | Kayoanime, JavHdz, Extractors (MissAV, SexTB, JavStream and MkvDrama also need this, on top of tier C) | `Context` + `SharedPreferences` from `Plugin.load(context)`; settings fragments. |
| **C: WebView / Cloudflare** | OmniStream, GoPlay, MkvDrama, MissAV, SexTB, JavStream, SupJav, OnlyKDrama, Extractors, `nexdrive` users | `CloudflareKiller`, `WebViewResolver`, `WebViewResolver.webViewUserAgent`. |
| **D: raw WebView** | StreamCorner | `android.webkit.WebView` + `JavascriptInterface` + `shouldInterceptRequest`. |
| **Excluded** | Fetcharr | Hooks into the app's UI (`ResultViewModel2`, `VideoClickAction`). Rebuild it as a native web UI action later if wanted. |

Work, in priority order:

1. **Tier A′:** set `isCrossPlatform = true` and rebuild. This is a change in the extensions repo; each plugin needs a
   version bump.
2. **Tier B:**
   - A desktop `android-shim` jar on the host classpath with minimal `Context`, `SharedPreferences` (backed by
     `~/.local/share/cloudstream-desktop/prefs/<plugin>.json`), `android.util.Log` and `android.util.Base64`.
   - Settings fragments are never instantiated. The web UI shows a generic key/value settings form per plugin instead.
     Optionally, plugins can declare a small settings schema later.
   - The cleaner long-term fix is in the extensions repo: move the Android-specific `Plugin` subclass and fragment
     behind a thin seam, the way `GoPlaySession` already does, so the core can be cross-platform.
3. **Tier C:** desktop implementations of `com.lagradost.cloudstream3.network.WebViewResolver` and `CloudflareKiller`
   with the **same class names and signatures**, backed by Chromium over CDP.
   - Use one shared, long-lived Chromium with a persistent profile, so cookies survive restarts.
   - `resolveUsingWebView(url, …)` navigates, matches requests against the regex, and returns the first matching
     request plus all requests seen.
   - `CloudflareKiller` is an OkHttp interceptor: on a challenge, solve it in Chromium, copy the `cf_clearance` cookie
     and user agent, then retry.
   - Turnstile often fails in pure headless mode. Support headed mode under Xvfb, or a visible window for manual solves.
   - Keep the repo's rules: one solve per host behind a `Mutex`, and at most `Semaphore(2)` requests per host after
     that.
4. **Tier D (StreamCorner):** first planned as a `StreamCornerEngine` seam in the extensions repo. Phase 10f instead
   emulates the slice of `android.webkit` that StreamCorner uses on the shared Chromium, so the plugin runs unchanged,
   including from its converted `.cs3`.

### 4.3 Server API (Ktor)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/providers` | Loaded providers: name, types, language, NSFW, status, load error. |
| GET | `/api/providers/{name}/home?page=` | `getMainPage` rows (one request per `mainPageOf` entry, lazy). |
| GET | `/api/search?q=&providers=` | **SSE**: query providers in parallel, stream results per provider as they finish, with a per-provider timeout. |
| GET | `/api/load?provider=&url=` | `load()` → normalized details (movie, series with seasons/episodes, anime with dub/sub, live). |
| GET | `/api/links?provider=&data=` | **SSE**: `loadLinks` streams `ExtractorLink`s and `SubtitleFile`s as the callbacks fire. Each link gets a short-lived id. |
| POST | `/api/play` | `{linkId, subtitleIds, title, episodeRef, startAt}` → mpv. |
| POST | `/api/player/{cmd}` | pause/seek/next/prev/stop/set-track. |
| WS | `/api/player/events` | Live player state for the UI: position, duration, paused, tracks, current item. |
| GET/POST | `/api/history`, `/api/bookmarks`, `/api/progress` | Persistence. |
| GET/PUT | `/api/settings`, `/api/plugins/{name}/settings` | App and per-plugin settings. |
| GET | `/proxy/{token}/…` | Header-injecting stream proxy (§4.4). |
| GET | `/api/stream?link=&direct=` | For a client's own player (§4.9): a link as a proxy path (or its URL and headers), its type and ClearKey key. 410 once the link set expired. |
| GET | `/api/subtitle?id=` | A subtitle fetched with its headers and served with a sniffed type (`text/x-ssa`, `text/vtt`, `application/x-subrip`, TTML). |
| GET | `/api/resume?provider=&data=` | Where to resume an episode. |
| POST | `/api/links/report` | `{link, success}`: a client's player records a link's result in the source's history. |
| GET | `/api/skips?malId=&aniListId=&episode=&duration=` | AniSkip ranges for an episode, without an mpv session. |

`POST /api/progress` also takes `{position, duration, label}` from a client's player; the first save puts the title in
the history.

History never records NSFW content. `GET /api/history` and `GET /api/continue` always exclude NSFW entries,
including older saved entries, regardless of the `show_nsfw` setting.

Normalize Cloudstream types into stable DTOs (`SearchItem`, `Details`, `Episode`, `Link`, `Subtitle`). The UI should
never depend on Cloudstream's class shapes. Cache `load()` results and link lists for a few minutes, keyed by
`(provider, url/data)`.

### 4.4 Links → mpv

- **Direct path (default):** pass `ExtractorLink.url` with `--http-header-fields`, `--referrer` and `--user-agent` from
  `link.headers` / `link.referer`. Subtitles are added with `sub-add`.
- **Proxy path:** used when the link needs something mpv can't send. Examples: cookies from an OkHttp interceptor such
  as CloudflareKiller, per-segment headers, or subtitle URLs that need headers. `/proxy/{token}` fetches through the
  plugin's own `app` client and rewrites M3U8 playlists so segment and key URIs go back through the proxy.
- **Plugin-local servers:** `KissKHLocalServer` and `ReanimeLocalServer` bind to `127.0.0.1:<random>` inside the JVM.
  This works because mpv runs on the same machine. LAN viewers only control mpv; they never fetch those URLs.
- **Types:** `M3U8` and `VIDEO` play directly. Pass `DASH` to mpv as-is. `DrmExtractorLink` is unsupported in v1:
  show it in the UI but disable it. Phase 10f enables single-key ClearKey through FFmpeg's `cenc_decryption_key`.
  Widevine stays unsupported.
- **Ranking:** sort links by `quality`, then by a per-source success history (§4.6).
- **Auto-play (default on):** the server starts the best link once 3 links have arrived or 6 s have passed since the
  first one, the same way OmniStream's race works. It keeps collecting links so they can still be picked from the sources
  sheet or the mpv source picker. Both numbers are settings, and auto-play can be turned off to always pick manually.

### 4.5 mpv manager

- On the **first play**, start one mpv: `mpv --idle=yes --force-window=yes
  --input-ipc-server=$XDG_RUNTIME_DIR/cs-mpv.sock --script=<repo>/mpv/cloudstream.lua` plus user flags from settings.
  Keep it open and idle between items. If you close the window, mark the player as stopped and start a new mpv on the
  next play; don't restart it on its own.
- The server runs as a systemd user service, so mpv needs the graphical session environment (`DISPLAY` /
  `WAYLAND_DISPLAY`). The unit imports it with `PartOf=graphical-session.target`, and the server checks it before
  launching mpv.
- Play with `loadfile <url> replace` and per-file options (`start=`, `http-header-fields=`, `force-media-title=`).
- Observe `time-pos`, `duration`, `pause`, `eof-reached`, `track-list` and `idle-active`, plus `end-file` with `reason`.
- Save progress every few seconds and on `end-file`. Past about 90%, mark the item as watched.
- If `end-file` reports `reason=error`, try the next link in the ranked list without user action (and say so on the
  OSD).
- Next episode: at about 85%, resolve the next episode's links in the background. On EOF, auto-play if enabled.

### 4.6 Persistence

SQLite (sqlite-jdbc) in `~/.local/share/cloudstream-desktop/`:

- `history(provider, url, title, poster, type, last_watched)`
- `progress(provider, episode_data, position, duration, watched)`
- `bookmarks(provider, url, title, poster, list)`
- `link_stats(provider, source_name, successes, failures)`, used for ranking.
- `settings(key, value)`

### 4.7 Web UI

- **Toolchain: Vite+** (`vp`, v1.4.0 installed locally). It manages Node and pnpm (`vp env`, `vp install`) and handles
  dev (`vp dev`), format/lint/type checks (`vp check`), tests (`vp test`) and the production build (`vp build`).
  - Scaffold with `vp create` in `web/`.
  - During development, `vp dev` proxies `/api` and `/proxy` to the Ktor server (`127.0.0.1:6969`). Ktor talks to
    the dev server only through that proxy.
  - `vp build` writes static files into `server/src/main/resources/web/`, which Ktor serves at `/`. A Gradle task
    runs `vp build` before `processResources`, so `./gradlew :server:run` always serves an up-to-date UI.
- **UI stack:** **Svelte 5** (runes) + TypeScript, using SvelteKit with `adapter-static` in SPA mode (`fallback:
  index.html`) for routing. There is no server-side rendering: Ktor serves the static build and falls back to
  `index.html` for unknown non-API paths.
  - Data: a small typed API client plus `@tanstack/svelte-query` for caching `home`/`load` results.
  - Use `EventSource` for the SSE endpoints and one WebSocket, wrapped in a shared rune store, for player state.
  - Check in Phase 3 that the SvelteKit Vite plugin runs under `vp dev` / `vp build`. If it doesn't, fall back to
    plain Svelte + Vite+ with a small client-side router.
- **Layout:** mobile-first, because the phone-as-remote case is a v1 goal. On a phone the now-playing bar becomes a
  full-screen remote: large play/pause and seek buttons, next episode, tracks and sources.
- **NSFW toggle:** off by default. While it's off, NSFW providers are hidden from the provider picker, search, home
  rows, continue watching and history. The server filters these, so no NSFW data reaches the browser.
- **LAN access:** see §7. When a phone opens the UI without a token, it shows a "scan the code on your desktop"
  screen.
- **Pages:**
  - **Home:** provider picker, the provider's home rows, and a "Continue watching" row from progress.
  - **Search:** one box that searches all enabled providers. Results arrive grouped by provider. Filter by type and
    hide NSFW by default.
  - **Details:** poster, plot, tags, cast and trailer. Season tabs and episode list with watched marks and resume
    position.
  - **Sources sheet:** opens on Play. Links stream in, grouped by source and sorted by quality. Auto-play the best one
    or pick manually. Choose subtitles.
  - **Now-playing bar** (always visible while mpv plays): title, progress, play/pause, ±10s, next episode,
    audio/subtitle track menus and source switching. This is what makes the phone-as-remote case work.
  - **Settings:** plugin source (dev path or repo URL), enable/disable plugins with load errors shown, per-plugin
    settings, mpv flags, NSFW toggle, auto-play/next-episode toggles and Chromium mode.

### 4.8 mpv script: `mpv/cloudstream.lua`

Uses only mpv built-ins (mpv 0.41 is installed). Menus use `mp.input.select` (mpv ≥ 0.39), and a **uosc**
integration can follow if uosc is installed.

- **OSD:** show the show name, `S1E3: Episode title` and the provider when a file starts.
- **Keybindings** (rebindable through `script-opts/cloudstream.conf`):
  - `Shift+N` / `Shift+P`: next or previous episode. Sends `cs-event {"type":"next"}`.
  - `Shift+S`: source picker. Asks the server for the current episode's link list and shows it in `mp.input.select`;
    picking one switches at the same timestamp.
  - `Shift+E`: episode picker for the current season.
  - `Shift+W`: mark watched and go to the next episode.
- **Fallback notices:** show the server's `script-message-to cloudstream osd "<text>"` messages, for example "Link
  failed, trying Movy 1080p…".
- **Loading state:** while the server resolves the next item, show a "Resolving links…" overlay so mpv never looks
  frozen. This needs `--idle` + `--force-window`.
- **Skip intro/outro (Phase 10c):** the server sends AniSkip ranges for anime. The script adds them as chapters and
  shows a "Skip" prompt, and the server does the seek, so the phone remote skips the same way.

The server stays the source of truth. The script sends intents and displays what the server tells it, so the browser
and the player can't disagree.

### 4.9 Android app (`android/`)

A second client that plays on the phone instead of driving mpv. It talks to the same `/api` with the pairing token as
a Bearer header (scanned from the QR), so plugins, links, history, progress and source profiles stay on the desktop.

- **Streams** go through `/proxy` by default, which covers interceptors, Cloudflare cookies and the plugin-local servers
  on the desktop's loopback. "Direct links" (a phone setting) plays a link's own URL when nothing needs the proxy.
- **Session rules** run on the phone with the server's code from `:shared`: `LinkRanking`, `SourceProfile`, `AutoPlay`,
  `SubtitlePreference`, the watched/prefetch fractions, and `SkipRule`. Fallback to the next link, next-episode
  prefetch, auto-next and the skip prompt behave as with mpv.
- **Player:** Media3 ExoPlayer (HLS, DASH incl. multi-period, ClearKey via a local license) with libass
  (`io.github.peerless2012:ass-media`) rendering ASS/SSA in an OpenGL overlay, side-loaded or inside MKV. A
  Cloudstream-style panel picks sources and subtitles together. Gestures: hold for 2×, horizontal scrub seek with live
  keyframe preview, double-tap ±10 s (accumulating), brightness/volume swipes, pinch to zoom, lock.

---

## 5. Repository layout (this repo)

```
cloudstream-desktop/
├── docs/PLAN.md
├── settings.gradle.kts / build.gradle.kts   # Gradle, Kotlin JVM, JDK 17 toolchain
├── server/                 # Ktor app: plugin host, API, mpv manager, proxy, persistence
│   └── src/main/kotlin/...
├── compat/
│   ├── android-shim/       # Context, SharedPreferences, Log, Base64 stubs (tier B)
│   └── webview-cdp/        # WebViewResolver + CloudflareKiller over Chromium CDP (tier C)
├── shared/                 # API DTOs and playback rules used by the server and the Android app
├── android/                # Android app: Compose UI + Media3 ExoPlayer (included when an Android SDK is found)
├── web/                    # Vite+ (vp) + Svelte 5/SvelteKit SPA → `vp build` into server resources
├── mpv/
│   ├── cloudstream.lua
│   └── cloudstream.conf    # default script-opts
└── packaging/
    ├── cloudstream-desktop.service   # systemd --user unit (runs the installed server jar)
    ├── cloudstream-desktop.desktop   # opens http://127.0.0.1:6969 in the browser
    └── install.sh                    # builds, installs to ~/.local/share, enables the unit
```

---

## 6. Changes needed in the extensions repo

Since v1.2 the desktop needs none of these: dev mode forces jars with an init script, and repo mode converts `.cs3`
files. They remain worthwhile cleanups for the plugins themselves. They go through that repo's normal flow, with a
version bump per plugin as its `CLAUDE.md` requires:

1. Set `isCrossPlatform = true` on the tier A′ modules after checking that they have no Android imports.
2. Move the `Context`/`SharedPreferences` use in Kayoanime, JavHdz, Extractors, MissAV, SexTB, JavStream and MkvDrama
   behind a seam, so the provider works without a `Context`. The settings UI stays Android-only.
3. ~~A `StreamCornerEngine` seam (tier D).~~ Not needed: Phase 10f emulates the `WebView` slice on the desktop.
4. A lightweight settings schema that the web UI can render, in place of the Android fragments. Phase 10e defines it:
   a `SETTINGS_SCHEMA` JSON constant on each `Plugin` class that has settings.

---

## 7. Configuration

`~/.config/cloudstream-desktop/config.toml`:

```toml
bind = "0.0.0.0:6969"            # LAN remote is a v1 goal; use 127.0.0.1 to keep it desktop-only
plugins.source = "repo"          # dev | repo; defaults to dev when dev_path holds a checkout (also on the Settings page)
plugins.repo_url = "https://raw.githubusercontent.com/NropVox/cloudstream-extensions-nropvox/builds/repo.json"
plugins.dev_path = "/mnt/AJ/Projects/cloudstream-extensions-istarvin"
plugins.build_on_start = true    # dev mode: run ./gradlew make for enabled modules at startup
mpv.path = "mpv"
mpv.extra_args = ["--profile=gpu-hq"]
playback.autoplay_best = true
playback.autoplay_next = true
ui.show_nsfw = false
chromium.path = "chromium"
chromium.mode = "headless"       # headless | xvfb | headed; also on the Settings page
chromium.idle_minutes = 10
network.max_requests_per_host = 2
```

**Auth.** Requests from loopback never need a token. Every other request needs one:

- The server generates a random token on first run and stores it in `~/.local/share/cloudstream-desktop/token`.
- The desktop UI's Settings page shows a QR code for `http://<lan-ip>:6969/pair?token=…`.
- Opening that link on the phone sets a long-lived `HttpOnly` cookie, so the token isn't typed by hand.
- The token can be rotated from Settings, which signs out every paired device.
- The `/proxy` endpoint is covered by the same check.

---

## 8. Phases

Each phase ends with a check that can be shown working. **v1 = Phases 0–6 (done).** Phase 7 hardens v1 (done).
**v1.1 = Phase 8 (done)**, **v1.2 = Phase 9 (done)**, and **v1.3 = Phase 10 (done; StreamCorner live check open)**.

**Phase 0: spike (de-risk the core decision)**
- Pin the `recloudstream` `library` JVM artifact (JitPack) to a commit that matches the `cloudstream3:pre-release`
  stub the plugins compile against. Write down the exact coordinates.
- Write a CLI that loads `Dulo.jar` with the pinned runtime classpath, then runs `search("dune")` → `load` →
  `loadLinks`, and prints the links.
- Repeat with `OmniStream.jar` (many shared sources, Rhino, local servers) and `Extractors` + `Javtiful` (to test
  `loadExtractor` across plugins).
- Investigate the repo note that "`newExtractorLink` needs the app's crypto library" and find what the JVM side needs.
- List which `com.lagradost.cloudstream3.network` classes (`WebViewResolver`, `CloudflareKiller`) exist in the JVM
  target. This decides how much of the Phase 5 stub work is needed.
- **Done when:** links from Dulo and OmniStream print, and one of them plays with `mpv <url>` plus headers.

**Phase 1: server core**
- Ktor app, `config.toml` loading and the plugin host (dev mode: Gradle build on start, hot reload on jar change).
- Normalized DTOs, plus `/providers`, `/home`, `/search` (SSE), `/load` and `/links` (SSE).
- The server filters NSFW results using `ui.show_nsfw`.
- **Done when:** all tier A plugins load. `curl` search, load and links work for at least one movie provider, one
  anime provider and one live provider. NSFW providers disappear when the toggle is off.

**Phase 2: mpv manager, proxy and persistence**
- mpv started on first play and kept idle afterwards, over IPC: `/play`, player commands, the WebSocket of player
  state and the header proxy with M3U8 rewriting.
- Auto-play the best link (3 links or 6 s), and fall back automatically to the next link on `end-file` errors.
- SQLite: progress, history, bookmarks and `link_stats`. Resume from the saved position.
- **Done when:** playing a header-protected HLS link works both directly and through the proxy, killing a link
  mid-play falls back to the next one, and replaying an episode resumes where it stopped.

**Phase 3: web UI (Svelte on Vite+)**
- Scaffold `web/` with `vp create` (SvelteKit + `adapter-static`), set up the dev proxy to Ktor, and hook `vp build`
  into the Gradle build. Confirm SvelteKit works under `vp`; if not, use plain Svelte.
- Mobile-first pages: home (with continue watching), search, details, the sources sheet, the now-playing bar (a
  full-screen remote on phones), history, bookmarks and settings (including the NSFW toggle).
- LAN auth: token, the QR pairing page and the pairing cookie.
- `vp check` and `vp test` pass.
- **Done when:** you can go from search to episode to playing in mpv without the terminal, then pair a phone by QR and
  control playback from it.

**Phase 4: mpv script**
- OSD, next/previous episode, the source and episode pickers, fallback notices and the loading overlay.
- **Done when:** a whole season plays with only mpv keybindings, and the web UI (desktop and phone) stays in sync.

**Phase 5: tier A′ + B plugins**
- Extensions repo: set `isCrossPlatform = true` on the tier A′ modules and bump their versions.
- This repo: the `android-shim` (`Context`, JSON-backed `SharedPreferences`, `Log`, `Base64`) and a generic
  per-plugin settings form in the UI.
- **Placeholder tier C classes:** where the JVM `library` lacks `WebViewResolver` / `CloudflareKiller`, provide stubs
  that throw a clear "needs Chromium support (v1.1)" error. That way OmniStream, Vegamovies, XPrimeHub and the other
  plugins still load, and only the sources that need a browser fail. Those failures are shown per source in the
  sources sheet.
- **Done when:** all tier A′ and B plugins load, Kayoanime/JavHdz/Extractors settings persist across restarts, and
  OmniStream returns links from its non-Chromium sources.

**Phase 6: packaging (v1 release)**
- `packaging/install.sh`: build the server's fat jar plus the UI, install them to `~/.local/share/cloudstream-desktop`,
  and install and enable `cloudstream-desktop.service` (systemd `--user`, `PartOf=graphical-session.target`,
  restart on failure) and the `.desktop` entry.
- **Done when:** after logging in, the UI is reachable from the desktop and from a paired phone without starting
  anything by hand, and Play opens mpv in the graphical session.

**Phase 7: v1 hardening (done; real-session check open)**

This phase settles v1 before Chromium adds more moving parts.

- **Real-session check:** run the installed service after a real login. Play opens a visible mpv window on X11 and on
  Wayland, the mpv keybindings and OSD work, and a real phone pairs by QR and works as a remote.
- **Concurrency:** add the global per-host `Semaphore(2)` from §9, wrapped around the plugins' shared `app` client so
  parallel search and links never exceed it.
- **Contain WebView failures:** catch `NotImplementedError` (and other `Error`s short of `VirtualMachineError`) in the
  `search`, `load` and `loadLinks` calls, and report them per source as "needs Chromium (v1.1)". One source then
  fails, not the whole plugin call.
- **TonkaCine `AEADBadTagException`:** find the cause (likely a JCE or GCM-tag-length difference from Android's
  provider) and fix it in the shim or upstream, or record it as an upstream bug.
- **Startup linkage report:** list every enabled plugin with load status and the first `NoSuchMethodError` or
  `NoClassDefFoundError` it hit, in the log and on the plugins page. This is the §9 mitigation for library drift.
- **Tests:**
  - Server: `Store` (resume, watched threshold, `link_stats` ranking), `Auth` (loopback, cookie, rotation, `/proxy`),
    the M3U8 rewrite with keys and nested playlists, and the auto-play rule (3 links or 6 s).
  - Web: the API client's SSE handling and the player-state store.
- **CI:** add a remote and a workflow that runs `./gradlew :server:test` and `vp check && vp test`. Plugin builds stay
  local, because they need the extensions repo and the Android SDK.
- **Done when:** the real-session check passes, a parallel search across every provider makes no more than 2
  concurrent requests to any host, a WebView-backed OmniStream source fails alone while its other sources return
  links, and CI is green.

**Phase 8 (v1.1): tier C, Chromium over CDP (done)**

- **Shadow the library classes.** Ship `com.lagradost.cloudstream3.network.WebViewResolver` in `compat/webview-cdp`
  with the library's exact constructor and method signatures. Put it before `library-jvm` on the host classpath so
  plugins resolve the desktop class. Add a startup assertion that the loaded class comes from the compat jar.
- **Chromium manager:**
  - One long-lived Chromium with a persistent profile in `~/.local/share/cloudstream-desktop/chromium/`, started
    lazily on the first request that needs it and stopped after it has been idle for a while.
  - Talk to it over CDP using a small WebSocket client in Ktor, without Playwright.
  - Settings: `chromium.path` and `chromium.mode` (`headless`, `xvfb` or `headed`), as in §7.
- **`WebViewResolver`:** open a fresh tab, enable `Network`, navigate, and match requests against `interceptUrl` and
  `additionalUrls`. Return the first match plus every request seen, honour the timeout, then close the tab.
  `webViewUserAgent` reports the real Chromium user agent.
- **`CloudflareKiller`:** on a challenge response, solve the challenge in Chromium, copy `cf_clearance` and the user
  agent into `savedCookies`, and retry the request. Allow one solve per host at a time, guarded by a `Mutex`.
  Requests that come back through the proxy carry the same cookies.
- **"Solve manually":** when a headless or xvfb solve times out, the UI offers a button that opens the page in a
  visible window. The original request retries once the cookie appears.
- **Done when:** GoPlay (through OmniStream), MkvDrama and one Cloudflare-fronted site such as MissAV resolve links
  and play. Cookies survive a server restart, and a forced Turnstile can be solved by hand from the UI.

**Phase 9 (v1.2): repo mode (done)**

- **Repo mode:** read `plugins.json` from the extensions repo's `builds` branch, download the cross-platform jars, and
  verify and cache them. Dev mode stays available, and the mode is chosen in Settings. First check whether the gradle
  plugin writes a jar URL for `isCrossPlatform` modules; if it doesn't, add a CI step upstream that publishes them.
- **Plugins without a published jar:** convert their `.cs3` with dex2jar. This replaced the planned upstream work
  (§6: `isCrossPlatform` on tier A′, a `Context` seam for tier B), which the desktop no longer needs.
- **Plugin updates:** show the installed and available versions, and offer one-click update and rollback.
- **Done when:** a fresh install with no local extensions repo loads every tier A–C plugin from the `builds` branch,
  and a plugin version bump upstream reaches the desktop through the UI.

**Phase 10 (v1.3): player polish, sources UI, plugin settings, StreamCorner (done; StreamCorner live check open)**

Six independent steps, smallest first. Each step ships and is checked on its own. The bullets below describe what
was built; see the Phase 10 results in the status section for what was verified live.

*10a. Next-episode prefetch status*
- `Session` keeps the `LinkSet` that `onTick` starts at 85%. A watcher on its `version` publishes
  `PlayerState.next = {label, status, links}`, where `status` is `waiting` (before 85%), `resolving`, `ready` or
  `failed`.
- If the prefetched set goes stale (`LinkService.TTL`, for example after a long pause), resolve it again while the
  position is still past 85%. That way `start()` on EOF always finds fresh links.
- Web: the now-playing bar and the phone remote show "Next: S1E4 · 6 links ready" next to the next-episode button.

*10b. Success-history ranking on the sources sheet*
- `Store.linkScores` also returns the raw `successes` and `failures`. `LinkDto` gains `order`, `score`, `successes`
  and `failures`, filled in when the link is emitted.
- `PlayerState` gains `failedLinks`: the ids tried this session that didn't play.
- Web: `sources.svelte.ts` sorts with the same comparator as `LinkService.ranked`: quality (Auto counts as 1079), then
  score, then arrival order. Today it sorts by quality only. Each source group shows its record ("worked 8 of 10", or
  "new"). The link that auto-play would pick is marked Best, and links that failed this session are marked as failed.
- The two comparators are tested on the same cases (Kotlin and `vp test`), so the sheet and auto-play can't disagree.

*10c. Skip intro/outro (AniSkip)*
- IDs: `Mapper.details` exposes `malId` and `aniListId` from the load response's `syncData`. AniPm, AnimeX,
  OmniAnime and Reanime set them through `addMalId` / `addAniListId` (Miruro doesn't). When only an AniList id is
  known, map it with AniList's GraphQL `Media(id) { idMal }` and cache the result.
  - On the JVM the library leaves the `syncData` keys (`malIdPrefix`, `aniListIdPrefix`, …) as `""`, so every id
    overwrote the others. `PluginRuntime.setSyncIdPrefixes` sets the app's values ("MAL", "AniList", "Kitsu",
    "SIMKL") at startup.
- `desktop.player.SkipTimes`: once mpv reports `duration`, fetch
  `api.aniskip.com/v2/skip-times/{malId}/{episode}?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=0`.
  Only `kind == anime` episodes with an episode number qualify. The request uses the host's own client, not the
  plugins' `app`. Results are cached in a new `skip_times(mal_id, episode, json, fetched)` table (found for a week,
  "not found" for a day).
  - `episodeLength=0` turns off AniSkip's own length filter, so one cached answer serves every link of an episode.
    `SkipTimes.matching` keeps, per type, the first result whose cut is within 15 s of the file's length.
  - If nothing matches, the episode plays as normal.
- Server → script: `skips {ranges: [{type, start, end}]}` after `file-loaded`. `PlayerState.skip = {type, end}` is
  set while the position is inside a range, so the web UI can show the same button.
- Script:
  - Merge the ranges into `chapter-list` as "Opening", "Ending" and "Recap".
  - While inside a range, show a "Skip opening" prompt and bind `key_skip` (default `TAB`). The key is bound only
    while the prompt is visible, so it doesn't take the key away the rest of the time.
  - The key sends `cs-event {"type":"skip"}`. The server seeks to the range's end, so mpv, the browser and the
    phone all skip the same way.
- New setting `playback.skip_segments = "prompt"` (`prompt | auto | off`). In `auto`, the server seeks when the
  position enters a range, once per range per file, so seeking back into an opening on purpose still works.

*10d. uosc integration*
- uosc isn't installed here, so install it for this step. `install.sh` doesn't bundle it.
- `script-message-to uosc` fails when uosc isn't loaded, so every call to uosc doubles as the presence check (the
  `uosc-version` broadcast can arrive before this script registers for it). Without uosc, or with `uosc=no` in
  `cloudstream.conf`, the pickers use `mp.input.select`.
- With uosc:
  - The source and episode pickers open as uosc menus (`open-menu` with `items: [{title, hint, value, active}]`,
    where `hint` carries the quality and the source's record from 10b).
  - The sources menu updates while links are still arriving (`update-menu`).
  - Skip ranges show on uosc's timeline for free, because they're chapters.
  - On uosc ≥ 5, the script registers previous/next episode, sources, episodes and skip buttons (`set-button`:
    `cs_prev`, `cs_next`, `cs_sources`, `cs_episodes`, `cs_skip`), which users can place in `uosc.conf` `controls`.
    `cs_skip` is hidden outside a skip range.

*10e. Per-plugin settings forms from a schema*
- Upstream (the extensions repo, with a version bump each): plugins with settings declare
  `const val SETTINGS_SCHEMA` on their `Plugin` class. These are OmniStream (Pixeldrain key), Extractors, Kayoanime
  (Drive API key), JavHdz, and MkvDrama once its new site is fixed. The value is a JSON string:
  `[{file, key, type: string|secret|boolean|int|choice, label, help, default, options}]`.
  - It's a plain string rather than a shared type, so the Cloudstream library doesn't change.
  - It's a static field, so it survives dex2jar and works for published jars and converted `.cs3` alike.
  - Android ignores it; its fragments stay as they are.
- Desktop: `PluginHost` reads the field by reflection at load and passes it on in `PluginDto.settingsSchema`.
  `PluginSettings.svelte` renders labelled fields: a password input for `secret`, a toggle, a number input or a
  select. Keys the plugin read that aren't in the schema stay editable below, under "Other", in the current generic
  form.

*10f. StreamCorner (tier D): emulate the slice of `android.webkit` it uses, on the shared Chromium*

This replaces the `StreamCornerEngine` seam that §4.2 planned. StreamCorner isn't `isCrossPlatform`, so repo mode
converts its `.cs3`. A seam would need the desktop engine inside the plugin, or a reflective hook into the host.
All of its WebView use sits in `StreamCornerBridge.kt`, and that surface is small. Emulating it runs the plugin
unchanged in both modes, with no upstream change, as the `CookieManager` shim already does.

- `compat/webview-cdp` adds `android.webkit.WebView`, `WebViewClient`, `WebSettings`, `WebResourceRequest`,
  `WebResourceResponse` and `JavascriptInterface`, plus `CookieManager.setAcceptThirdPartyCookies`. Delegation is
  parent-first, so these win over the android.jar stubs, as `CookieManager` does today. Each `WebView` is one tab of
  the shared `desktop.cdp.Chromium`.
  - `loadUrl` maps to `Page.navigate`, and `evaluateJavascript` to `Runtime.evaluate`.
  - `shouldInterceptRequest`: `Fetch.enable` on every request at the Request stage. `Fetch.requestPaused` calls the
    client on a worker thread, then answers with `Fetch.fulfillRequest` (status, headers, body) or
    `continueRequest`. `isForMainFrame` means a Document request in the top frame.
  - `shouldOverrideUrlLoading`: top-frame Document requests after the `loadUrl` one are offered to the client. A
    `true` answer becomes `Fetch.failRequest(Aborted)`, which keeps pop-unders and the devtools trap off the page.
  - `addJavascriptInterface(obj, name)`:
    - Find the `@JavascriptInterface` methods by reflection.
    - Define `window[name].method(…)` with `Page.addScriptToEvaluateOnNewDocument`. Each stub posts `{o, m, a}` to
      `/__cs_javabridge__` on the page's own origin with a synchronous XHR, which the request interception answers
      with the method's return value.
    - So calls run in order and return values reach the page, as on Android. A CDP binding was the first plan, but
      `Runtime.addBinding` doesn't survive the cross-process navigation without `Runtime.enable`, which pages can
      detect (the site has a devtools trap).
    - Methods run on a "JavaBridge" thread, with String, Int, Long, Float, Double and Boolean arguments.
  - Out-of-process iframes are auto-attached (`Target.setAutoAttach`, paused until set up) and get their own request
    interception. Dedicated workers have no `Fetch` domain; their requests pause in the page's session, so a worker is
    only resumed, once on attach and again on `Inspector.workerScriptLoaded` (a resume before its script has loaded
    is ignored, and a module worker stayed paused).
  - `settings.userAgentString` reports Chromium's real user agent. `layout(…)` maps to
    `Emulation.setDeviceMetricsOverride`. The remaining `WebSettings` setters do nothing. `destroy` closes the tab.
  - Members outside the slice don't exist, so a plugin that calls one fails with `NoSuchMethodError`
    (`LinkageCheck` skips `android/` references, so this shows at call time, not at load). An API-parity test checks
    every emulated member against android.jar's signature, as the `WebViewResolver` shadow test does.
  - Debug aid: `-Dcs.debug.webview` prints every CDP event and request decision of the emulated WebViews.
- `Dispatchers.Main`: the JVM has none, and StreamCorner is the only plugin that uses it. A
  `kotlinx.coroutines.internal.MainDispatcherFactory` service (one "main" thread) in `compat/android-shim` provides
  it, next to a minimal `android.net.Uri` (what `WebResourceRequest.getUrl()` returns).
- ClearKey: StreamCorner's Amazon feeds are single-key ClearKey DASH (`DrmExtractorLink` with a `kid` and `key`).
  FFmpeg 9 (installed) has `cenc_decryption_key` on its DASH demuxer. So single-key ClearKey links become playable:
  - Convert the key from base64url (or hex) to hex, and pass the per-file option
    `demuxer-lavf-o=cenc_decryption_key=<hex>` (`ClearKey.hexKey`, `ResolvedLink.clearKey`).
  - Mark these links "ClearKey" on the sheet.
  - Widevine stays out of scope. Multi-key feeds stay disabled.
  - A manifest carried inline in `extractorData` (StreamCorner's NBA replays, HLS fMP4 re-described as a DASH
    `SegmentList`) plays when the plugin's `getVideoInterceptor` serves it at the link's URL. The link is proxied, and
    `StreamProxy.rewriteMpd` sends its segment URLs through the proxy too, so the link's headers apply. It also gives
    each `SegmentList` a `duration`: FFmpeg ignores the timeline there and can't seek without one.
  - FFmpeg's DASH demuxer reads only the first `<Period>`, so a multi-period manifest (HLS discontinuities, e.g. the
    full-game replays) is split into one manifest per period (`StreamProxy.periodPaths`) and mpv joins them as
    `edl://`. Manifests the proxy makes answer `Range` requests, which FFmpeg uses to re-read them.
  - Checked against a locally encrypted CENC `SegmentList` stream (video and audio, absolute URLs): decrypts with the
    key and is garbage without it. The same stream as two periods plays end to end through the proxy and mpv, with
    the upstream demanding a `Referer`. Not yet checked against a live NBA replay.

**Done when:**
- **Prefetch:** at 85%, the now-playing bar shows the next episode go from `resolving` to `ready` with a link count.
  On EOF, the next episode starts from the prefetched set without a new `loadLinks`.
- **Ranking:** the sources sheet's order matches the server's auto-play choice, and it shows each source's record
  and this session's failed links.
- **Skip:** an anime episode from OmniAnime or AniPm shows Opening/Ending chapters. The skip prompt appears in mpv and
  on the phone remote, and both skip. `auto` skips with no input. A title without AniSkip data plays normally.
- **uosc:** with uosc installed, the pickers open as uosc menus, and the sources menu fills while links resolve.
  Without uosc, `mp.input.select` still works.
- **Settings:** OmniStream and Kayoanime settings render as labelled forms from their schema, in dev mode and from
  converted `.cs3` files in repo mode. Keys outside the schema stay editable.
- **StreamCorner:** it loads in dev and repo mode, home and load work, and an HLS stream and a ClearKey DASH feed both
  play in mpv. No Chromium tabs are left open after 10 plays.
- **Tests (new):**
  - AniSkip parsing and its cache, and the auto-skip rule (once per range).
  - Ranking parity, in Kotlin and in web tests.
  - Reading the schema from a jar and from a converted `.cs3`.
  - The WebView shim against a local page: intercept and fulfill, a JS bridge round trip with return values, an
    out-of-process iframe's requests, a module worker driven through `evaluateJavascript`, a blocked top-frame
    navigation, no tabs left after `destroy`, and the API-parity test.
  - Sync ids (MAL and AniList both survive) and ClearKey key formats.
  - The Main dispatcher.

**Later (not planned yet)**

- Fetcharr stays excluded (§4.2). It can come back as a native web UI action.
- **Still out of scope:** downloads, Widevine DRM, and running the server on a different machine from mpv.

---

## 9. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| The `library` JVM artifact drifts from the `pre-release` stub the plugins compile against (`NoSuchMethodError`). | Plugins fail at runtime. | Pin one commit. Add a startup check that loads every plugin and reports linkage errors per plugin. Build the library from source if JitPack is flaky. |
| The JVM `library` has no `CloudflareKiller`, and its `WebViewResolver.resolveUsingWebView` is `TODO()`. | Tier C sources throw `NoClassDefFoundError` or `NotImplementedError`, which can fail a whole `loadLinks` call. | Done: the CDP classes in `compat/webview-cdp`, ordered before `library-jvm`, with a startup check and an API-parity test. |
| Turnstile / Cloudflare fails in headless Chromium (v1.1). | Tier C sources fail. | Headless runs the full browser (`--ozone-platform=headless`, fixed debugging port), which passes GoPlay's Turnstile. For stricter checks: xvfb or headed mode, a persistent profile, and a "solve manually" button in the UI that opens the visible browser. |
| The concurrency limits in the repo's `CLAUDE.md` (OkHttp deadlock at 5+ parallel requests per host with CloudflareKiller). | Hangs during parallel search. | Done: OkHttp `maxRequestsPerHost = 2` on the shared dispatcher, and a `CloudflareKiller` without nested requests. Per-provider timeouts in search and links. |
| Links that rely on interceptors or cookies won't play directly in mpv. | Playback errors. | The proxy path. Mark each link "direct" or "proxied" from its headers and whether its host needs an interceptor. |
| Jackson/Rhino version conflicts with Ktor's dependencies. | Odd runtime errors. | Keep the server's own JSON on kotlinx.serialization. Isolate the plugin runtime in its own class loader layer if needed. |
| The server listens on the LAN by default. | Anyone on the network could control playback or use the proxy. | A token is required for every non-loopback request, including `/proxy`. Pairing by QR, with token rotation. |
| A systemd user service has no display environment. | mpv fails to open a window. | `PartOf=graphical-session.target` and importing the environment into the unit. The server checks for a display before launching mpv and shows a clear error in the UI. |
| dex2jar mistranslates a `.cs3` (repo mode, plugins without a published jar). | A converted plugin fails to load or misbehaves. | Any method dex2jar can't translate fails the conversion. `LinkageCheck` and the JVM verifier run on the result. The converter version triggers reconversion after a fix, and dev mode and published jars avoid conversion altogether. |
| The `android.webkit` emulation (Phase 10f) drifts from how StreamCorner uses WebView, or a new plugin needs more of it. | StreamCorner breaks after an upstream change, or a new tier D plugin fails. | Only the members StreamCorner uses are emulated; others fail with `NoSuchMethodError`. The API-parity test, plus a check of `StreamCornerBridge.kt` changes on each StreamCorner bump. A seam upstream remains the fallback. |
| SvelteKit doesn't work under `vp`. | Phase 3 setup takes longer. | Fall back to plain Svelte 5 + Vite+ with a small client-side router. |
