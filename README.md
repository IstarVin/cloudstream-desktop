# Cloudstream Desktop

Run Cloudstream plugins on your own server, browse from any device, and watch in mpv,
your browser, or the Android app. One server hosts the plugins and keeps your library
and watch progress together across desktop, phone, tablet, and Android TV.

## Features

- **Browse and search across providers.** Explore provider home feeds, search multiple
  providers with results arriving as they load, and open title pages with seasons,
  subbed/dubbed episodes, cast, trailers, and recommendations when supplied by the provider.
- **Watch where you want.** Play on the desktop with mpv, directly in a browser, or in
  the native Android app. Install the web UI as a PWA for its own app window and launcher icon.
- **Keep your place.** Resume playback, pick up from Continue watching, track watched
  episodes, and organize titles into Watching, Planned, Completed, and Dropped lists.
  History, library, and progress are stored on the server in SQLite.
- **Choose better sources.** Seven named priority profiles rank sources and video quality.
  Auto-play selects a suitable link while others keep resolving; automatic fallback tries
  another source after a failure. Pick a source manually or hide failed and negative-priority links.
- **Control playback across devices.** Send playback to the desktop, an open browser tab,
  or another connected Android phone/TV. Use the now-playing remote or continue an episode
  on another device from the player's cast button.
- **Subtitles and episode controls.** Styled ASS subtitles, audio/subtitle selection,
  automatic next-episode playback, and AniSkip opening/ending prompts or automatic skips
  where timing data is available. The mpv script adds on-screen pickers and optional uosc integration.
- **Manage Cloudstream plugins.** Load a published repository, check for updates, update
  or roll back plugins, and edit plugin settings. For extension development, build a local
  checkout and hot-reload its plugin jars.
- **Browser support for providers.** Optional Chromium handles plugins that need a real
  browser or Cloudflare checks, with headless, Xvfb, and visible-window modes plus a Solve banner
  for checks that need interaction.

## Choose a player

| Client | Playback | Highlights |
| --- | --- | --- |
| Desktop | mpv on the server machine | On-screen source/subtitle pickers, AniSkip chapters, optional uosc menus |
| Web / installed PWA | In the browser, or control another connected player | HLS/DASH playback, styled subtitles, desktop and phone layouts |
| Android phone / tablet | Media3 ExoPlayer on the device | libass subtitles; gestures for seeking, brightness, volume, speed, and zoom |
| Android TV | Media3 ExoPlayer on the TV | D-pad navigation, focus outlines, remote-friendly pickers, and media-key controls |

All clients need the server running and reachable. Browser playback depends on the
browser's supported codecs. Casting connects Cloudstream clients while they are open;
receiving can be configured in **Settings → Playback → Casting**.

## Quick start

### Run from source

You need a **JDK 17+** and **Vite+ (`vp`)** available on your PATH. Install **mpv 0.39+**
for desktop playback; Chromium or Google Chrome is optional for providers that need it.

```bash
./gradlew :server:run
```

The build installs web dependencies and bundles the UI. Open **http://127.0.0.1:6969**,
choose your providers, and browse or search for a title.

The first start writes `~/.config/cloudstream-desktop/config.toml`. Plugin loading
uses a local extensions checkout when the configured path exists, otherwise the published
repository. Change the source in **Settings → Plugins** on the desktop:

| Source | Configuration | Behavior |
| --- | --- | --- |
| Published repository | `plugins.source = "repo"`, `plugins.repo_url` | Accepts a Cloudstream `repo.json` or `plugins.json`; loads JVM jars or converts `.cs3` files with dex2jar; verifies supplied hashes |
| Local extensions checkout | `plugins.source = "dev"`, `plugins.dev_path` | Builds with Gradle and hot-reloads plugin jars; needs the extensions repo's Android SDK configuration (`local.properties`) |

Plugin compatibility depends on the extension; conversion does not guarantee every
Android plugin will run on the desktop JVM. Settings shows plugin status and load errors.

On Linux with systemd, install a user service and desktop launcher:

```bash
packaging/install.sh
```

Re-run the installer to update. Use `packaging/install.sh --uninstall` to remove the
installation while keeping data and settings.

### Run with Docker

The headless image includes Chromium and serves the web UI and Android clients. It
has no mpv; play in a browser or on an Android device. Published images are amd64.

```bash
docker run -d --init --name cloudstream -p 6969:6969 --shm-size=1g \
  -v cs-config:/config -v cs-data:/data ghcr.io/istarvin/cloudstream-desktop:latest
```

Or build the image locally with `docker compose up -d --build`.

Settings live in `/config/cloudstream-desktop/config.toml`, seeded from
[`docker/config.toml`](docker/config.toml). Restart the container after editing the file.
The named volumes preserve configuration and data across container replacement.

Container access requires pairing, including from the host browser. Read the token:

```bash
docker exec cloudstream cat /data/cloudstream-desktop/token
```

Then open `http://<host>:6969/pair?token=<token>`. Keep port 6969 on your trusted network;
do not expose it directly to the internet.

### Connect a phone, tablet, or TV

On the desktop, open **Settings → Pair a phone**. Scan the QR code from the Android
app or enter the server address and token manually. On Android TV, enter the address
and token shown below the code. A browser on another device can open the pairing link.

Build the Android app locally with an Android SDK configured through `ANDROID_HOME`
or `local.properties`:

```bash
./gradlew :android:assembleDebug
# APK: android/build/outputs/apk/debug/android-debug.apk
```

The app supports **Android 8.0+**. The Android module is included in the Gradle build
only when an SDK is found, so server-only development does not require one.
Signed release APKs are published through the Android release workflow; see
[GitHub Releases](https://github.com/istarvin/cloudstream-desktop/releases) for published builds.

On TV, use the D-pad and Select to navigate. During playback, Select/Up/Down reveals
controls; Left/Right seeks when controls are hidden. Back closes a picker, hides
controls, then returns to the title. Media keys support play/pause, seeking, and
previous/next episode.

### Install the web app

Serve a production build (`./gradlew :server:run` builds it), then open the UI:

- **Chrome / Edge on desktop:** use the address-bar install icon or the browser menu's install option.
- **Chrome on Android:** choose **Install app / Add to Home screen** from the browser menu.
- **Safari on iPhone / iPad:** choose **Share → Add to Home Screen**, with **Open as Web App** enabled when offered.

PWA installation requires a secure origin. `http://localhost:6969` and
`http://127.0.0.1:6969` work on the server's own computer. On another device, use HTTPS
with a trusted certificate, for example through a reverse proxy; a plain HTTP LAN
address does not qualify.

The PWA still needs a reachable server. Its service worker caches a connection-help
page with a retry button, rather than library data, pairing tokens, or streams.
Updates activate after existing app windows and tabs close to avoid interrupting playback.

## Playback preferences

Use **Settings → Source priority** or the **Sources** sheet to select and edit one of
seven profiles. Source scores and quality scores combine to rank links across clients.
With auto-play enabled, a playable link with priority **10 or higher** starts immediately
while the rest keep loading. Otherwise, playback starts with the best link when resolution
finishes. Profiles can hide failed or negative-priority links, and failed links are not
retried automatically within the same playback session.

See [Source profiles](docs/SOURCE_PROFILES.md) for scoring and configuration details.
Playback settings also control next-episode auto-play, stream proxying, and AniSkip behavior.
Android includes the server's browser settings, Cloudflare Solve banner, and dev-mode Rebuild.

## Development

| Task | Command |
| --- | --- |
| Server and shared tests | `./gradlew :shared:test :server:test` (Chromium tests skip when it is unavailable) |
| Android build and lint | `./gradlew :android:assembleDebug :android:lintDebug` |
| Android TV UI tests | `ANDROID_SERIAL=emulator-5556 ./gradlew :android:connectedDebugAndroidTest` |
| Web UI with hot reload | `cd web && vp dev` (proxies `/api` to a running server) |
| Web checks and tests | `cd web && vp check && vpr check && vp test` |
| Release tooling tests | `python3 -m unittest discover -s scripts -p 'test_*.py' -v` |
| Plugin CLI smoke check | `./gradlew :server:run --args="spike Dulo dune"` |

TV UI tests use television configuration on a phone emulator or TV image. Select a
disposable emulator with `ANDROID_SERIAL` so they do not run on a personal device.

In dev mode, plugins hot-reload when `<Module>/build/<Module>.jar` changes in the
extensions checkout. **Settings → Plugins → Rebuild** runs
`gradlew -I desktop-jars.init.gradle :Name:compilePluginJar`, including for modules
that are not marked `isCrossPlatform`.

For debugging, run the server with `-Dcs.debug.failSources="Dulo Pickle,…"` to exercise
source fallback, or `-Dcs.debug.webview` to log CDP events and request decisions to stderr.

See [Releases](docs/RELEASING.md) for independent Android/server versioning, the
release helper and skill, signing secrets, tags, and publishing workflows.

## Project layout

| Path | Purpose |
| --- | --- |
| [`server/`](server/) | Ktor server, plugin host, API, mpv manager, stream proxy, and SQLite storage |
| [`shared/`](shared/) | API models and shared playback rules: ranking, source profiles, subtitles, and skips |
| [`web/`](web/) | SvelteKit SPA on Vite+, bundled into the server |
| [`android/`](android/) | Compose app with Media3 ExoPlayer and libass |
| [`compat/android-shim/`](compat/android-shim/) | Desktop implementations of the Android and Cloudstream interfaces plugins need |
| [`compat/webview-cdp/`](compat/webview-cdp/) | WebView and Cloudflare compatibility through Chromium over CDP; precedes `library-jvm` on the classpath |
| [`mpv/`](mpv/) | Player script, on-screen controls, optional uosc integration, and default keybindings |
| [`testdata/`](testdata/) | Shared link-ranking test cases |
| [`packaging/`](packaging/) | systemd user service, desktop entry, and installer |
| [`.github/workflows/`](.github/workflows/) | CI checks, Android APK releases, and Docker publishing |

For architecture and design context, see the [project plan](docs/PLAN.md) and
[design system](DESIGN.md).
