# Cloudstream browser extension

Open or play titles from a provider's own site in your Cloudstream Desktop server. It works on any site whose
host matches a loaded provider's `mainUrl`.

- **Right-click a link** on a provider site: **Open in Cloudstream** opens the server's title page in a new tab;
  **Play on ▸** starts the title on mpv or another connected device (the movie, or the episode to resume or watch next).
- **On a provider page**, a small button in the bottom-right corner does the same for the page you're on. Its
  menu can also hide it on that site (the popup can show it again). The toolbar icon shows ▶ on provider sites.
- If the URL isn't a title (a genre or search page), the title page or the play notification shows the provider's error.

Built with [WXT](https://wxt.dev/), Svelte 5 and Lucide; Manifest V3 for Chrome and Firefox.

## Install

From a GitHub release tagged `extension-v*`:

- **Chrome, Edge, Brave:** unzip `cloudstream-extension-<version>-chrome.zip`, open `chrome://extensions`, turn on
  Developer mode and choose **Load unpacked** on the unzipped folder.
- **Firefox:** open `cloudstream-extension-<version>-firefox.xpi`. An unsigned `-firefox-unsigned.zip` installs
  only in Developer Edition or Nightly with `xpinstall.signatures.required` set to false, or temporarily via
  `about:debugging`.

The setup page opens after installing (later, use the toolbar button):

1. Enter the server's address, the same one you open the web UI at.
2. Firefox: choose **Allow access to all sites** (Chrome grants it at install).
3. Choose **Sign in**, open the approval page, and approve the code as you would for a TV. The extension keeps
   its own session, listed under **Settings → Account & devices → Signed-in devices** in the web UI, where you can revoke it.

The server must have sign-in configured (see the main README). Providers are refreshed every 30 minutes, or from
the popup's refresh button.

## Develop

```bash
pnpm install
pnpm dev               # Chrome with hot reload (pnpm dev:firefox for Firefox)
pnpm format:check && pnpm lint && pnpm check && pnpm test
pnpm zip && pnpm zip:firefox   # .output/*.zip
```

`src/entrypoints/background.ts` makes every request to the server, holds the token, builds the context menus and
sets the badge. The popup (`src/entrypoints/popup/`) and the page button (`src/entrypoints/site.content/`, in a
shadow root) talk to it through messages (`src/lib/messages.ts`), so the token never reaches a web page. Matching
lives in `src/lib/match.ts`. API types come from the web UI (`web/src/lib/types.ts`, the `$shared` alias).

Server endpoints it uses: `/api/auth/status`, `/api/auth/device`, `/api/auth/device/poll?client=extension`,
`/api/auth/logout`, `/api/providers`, `/api/cast/devices` and `/api/play-title`. The server's
Origin check accepts `chrome-extension://` and `moz-extension://` origins only with a Bearer token or on the
device sign-in endpoints (`Auth.isExtension`).

Releases: `extension-v*` tags through `scripts/release.py` (see [docs/RELEASING.md](../docs/RELEASING.md)).
