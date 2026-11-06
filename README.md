# Cloudstream Desktop

Run Cloudstream plugins on your own server, browse from any device, and watch in mpv,
your browser, or the Android app. One server hosts the plugins and keeps your library
and watch progress together across desktop, phone, tablet, and Android TV.

[![CI](https://github.com/IstarVin/cloudstream-desktop/actions/workflows/ci.yml/badge.svg)](https://github.com/IstarVin/cloudstream-desktop/actions/workflows/ci.yml)
[![Docker](https://github.com/IstarVin/cloudstream-desktop/actions/workflows/docker.yml/badge.svg)](https://github.com/IstarVin/cloudstream-desktop/actions/workflows/docker.yml)

[Quick start](#quick-start) · [Configuration](#configuration) ·
[Development](#development) · [Troubleshooting](#troubleshooting) ·
[Releases](https://github.com/IstarVin/cloudstream-desktop/releases)

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

You need **Git**, a **JDK 17+**, and the web toolchain. CI and Docker use **Node.js 26**
and **pnpm 12.16.1**; the project installs Vite+ locally. Install **mpv 0.39+** for
desktop playback; Chromium or Google Chrome is optional for providers that need it.
The repository includes the Gradle wrapper, so a separate Gradle install is unnecessary.

```bash
git clone https://github.com/IstarVin/cloudstream-desktop.git
cd cloudstream-desktop
npm install -g pnpm@12.16.1
cd web
pnpm install --frozen-lockfile
cd ..
export PATH="$PWD/web/node_modules/.bin:$PATH"
./gradlew :server:run
```

Run subsequent Gradle commands from the repository root with that PATH set. The build
installs web dependencies and bundles the UI. Open **http://127.0.0.1:6969**, choose your
providers, and browse or search for a title. Select an episode or movie, choose a player,
and let auto-play pick a source or select one manually from **Sources**.

To check that the server is reachable:

```bash
curl --fail http://127.0.0.1:6969/api/auth/status
```

This returns JSON describing authorization and setup status without requiring a session. Loopback access also requires sign-in.

The first start writes `~/.config/cloudstream-desktop/config.toml`. Plugin loading
uses a local extensions checkout when the configured path exists, otherwise the published
repository. Change the source in **Settings → Plugins** on the desktop:

| Source | Configuration | Behavior |
| --- | --- | --- |
| Published repository | `plugins.source = "repo"`, `plugins.repo_urls` | Accepts one or more Cloudstream `repo.json` or `plugins.json` URLs (the earlier wins on a duplicate plugin); loads JVM jars or converts `.cs3` files with dex2jar; verifies supplied hashes |
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
docker run -d --init --name cloudstream -p 6969:6969 --shm-size=1g --env-file .env \
  -v cs-config:/config -v cs-data:/data ghcr.io/istarvin/cloudstream-desktop:latest
```

Set `TZ` in `.env` to your own timezone (for example, `Asia/Manila`). Cloudflare compares the
browser's timezone with your IP's location, and a container defaults to UTC, which makes
sites such as OnlyKDrama and GoPlay reject the background Chromium. Compose reads `TZ`
from your shell (`TZ=Asia/Manila docker compose up -d --build`), or edit `docker-compose.yml`.

Or build the image locally with `docker compose up -d --build`.

The `latest` image tracks the default branch. Versioned server releases publish
`ghcr.io/istarvin/cloudstream-desktop:<version>`; see the release notes for the tag
and image digest. With Compose, use `docker compose logs -f cloudstream` for logs
to inspect startup and runtime errors.

Settings live in `/config/cloudstream-desktop/config.toml`, seeded from
[`docker/config.toml`](docker/config.toml). Restart the container after editing the file.
The named volumes preserve configuration and data across container replacement.

### Authentication and connecting clients

Deploy the [central authentication Worker](auth-worker/README.md), then copy
[.env.example](.env.example) to `.env` and configure `CLOUDSTREAM_AUTH_URL`,
`CLOUDSTREAM_AUTH_SECRET` (the Worker's `SERVER_SECRET`), and a server name.
Compose passes these to the container. Native server processes use the same environment
variables. Set `CLOUDSTREAM_PUBLIC_ORIGIN` when an HTTPS reverse proxy terminates TLS.

There is one owner account, seeded from Worker secrets `OWNER_USERNAME` and
`OWNER_PASSWORD`. Change either later on the Worker site's **Account settings** page.
The desktop browser, web clients, Android phones, and TVs all require sign-in.

On Android, enter your server address. On web, open the server's normal URL.
Request a sign-in code, open the displayed Worker address on another device,
sign in, and enter the six-character code. Confirm the displayed server and device.
The waiting device signs in automatically. Android TV defaults to this method.
Username/password entry is also available over HTTPS or localhost connections.

Each device has a separate 30-day session. Web **Settings → Account & devices**
can sign out individual devices. Changing credentials or signing out all devices
on the Worker invalidates sessions on every server within one minute. Existing
pairing tokens and `/pair?token=…` links no longer work after this upgrade.

Use HTTPS for a publicly reachable server; HTTP is suitable only on a trusted LAN.
Read the [authentication contract](docs/authentication.md) for session storage,
Worker outage behavior, and API details.

### Build the Android client

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

In the Android app, open **Settings → App updates → Check for updates**. Available
stable Android releases download inside the app with progress and a cancel action.
After verifying the APK checksum, the app prompts you to install it. Android may
first ask you to allow Cloudstream to install apps; installation still requires
your confirmation. Updates must have a higher version code and use a compatible
signing key.

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
page with a retry button, rather than library data, session tokens, or streams.
Updates activate after existing app windows and tabs close to avoid interrupting playback.

## Configuration

The first launch creates the config file. Edit it and restart the server to change
startup settings. See the full [desktop defaults](server/src/main/resources/default-config.toml)
and [Docker defaults](docker/config.toml).

| Setting | Desktop default | Purpose |
| --- | --- | --- |
| `bind` | `"0.0.0.0:6969"` | Listen on all interfaces; set `"127.0.0.1:6969"` for access only from this computer |
| `mpv.enabled` | `true` | Start mpv on the server; Docker defaults to `false` |
| `mpv.path` | `"mpv"` | Executable name or full path |
| `chromium.path` | `"chromium"` | Browser executable for providers that need it |
| `chromium.mode` | `"headless"` | `headless`, `xvfb` (requires Xvfb), or `headed` (requires a display) |
| `chromium.idle_minutes` | `10` | Stop Chromium after this many unused minutes |
| `network.max_requests_per_host` | `2` | Limit concurrent plugin requests to each host |

Settings marked `(UI)` in the config can also be changed in **Settings**. Values saved
through the UI are stored in SQLite and take precedence over config file defaults,
including after a restart. Plugin source and repository changes require loopback access
from the server computer.

### Data locations

| Content | Desktop path | Container path |
| --- | --- | --- |
| Configuration | `~/.config/cloudstream-desktop/config.toml` | `/config/cloudstream-desktop/config.toml` |
| Database, plugins, browser profile, hashed sessions | `~/.local/share/cloudstream-desktop/` | `/data/cloudstream-desktop/` |

Desktop paths respect `XDG_CONFIG_HOME` and `XDG_DATA_HOME` when set. The SQLite database
is `cloudstream.db` in the data directory. Stop the server before copying configuration
and data for a consistent backup; keep both Docker volumes when replacing a container.

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
| Android unit tests | `./gradlew :android:testDebugUnitTest` |
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

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Gradle cannot find `vp` | Install web dependencies and add `web/node_modules/.bin` to PATH as shown in Quick start; keep that environment for the installer too |
| Gradle cannot find the `android` project | Configure a valid SDK with `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in root `local.properties`, then run Gradle again |
| A phone or TV cannot connect | Use the server's LAN address, check the bind address and firewall for TCP 6969, and sign in on the device |
| Docker asks the host browser to sign in | Configure the authentication Worker environment variables, then sign in using a device code |
| A config edit has no effect | Restart for startup settings; for UI settings, change the saved value in Settings because it overrides the file |
| A provider fails to load or resolve links | Check plugin status and load errors in Settings; confirm Chromium is installed if that provider needs a browser, and use the Solve banner when offered |
| Cloudflare sites fail in Docker (Solve banner, "Timed out") | Set `TZ` to your timezone on the container; the default UTC is flagged as a bot when it doesn't match your IP |
| Browser playback fails for a source | Try another source or play in mpv or Android; browser codec support varies |
| The browser offers no PWA install option | Use localhost on the server computer or HTTPS with a trusted certificate on another device |

For a Linux user-service installation, follow logs with
`journalctl --user -u cloudstream-desktop -f`. Source runs log to the terminal;
a container started with the `docker run` command above logs with
`docker logs -f cloudstream`; under Compose use `docker compose logs -f cloudstream`.

## Contributing and support

Report bugs and request features through [GitHub Issues](https://github.com/IstarVin/cloudstream-desktop/issues).
Include the client, server setup, reproduction steps, and relevant logs. Remove session
tokens and other credentials before sharing logs or configuration.

For code changes, follow the source setup above, keep changes focused, and run the
relevant checks from the Development table before opening a pull request. Changes to
source ranking must update the Kotlin and web implementations together and keep the
shared cases in `testdata/` passing. See [source profiles](docs/SOURCE_PROFILES.md)
for the scoring rules and [release documentation](docs/RELEASING.md) for publishing.

## License and acknowledgments

This repository does not currently include a license file. No project license is
declared here; dependencies and third-party plugins retain their own licenses.

Cloudstream Desktop builds on Cloudstream's plugin ecosystem, Ktor, SvelteKit,
mpv, Android Media3, and libass.
