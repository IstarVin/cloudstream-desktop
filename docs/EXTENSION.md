# Browser extension design

A browser extension that lets you open or play a title straight from the original site when that site matches a loaded provider's `mainUrl`. Implemented in [`extension/`](../extension/); user setup is in its README.

## Decisions

| Topic | Decision |
|---|---|
| Framework | [WXT](https://wxt.dev/), Svelte 5 (runes), `@lucide/svelte` icons |
| Browsers | Chrome (MV3) + Firefox (MV3; MV2 build only if a Firefox API gap forces it) |
| Scope (v1) | Context menu on links + a floating button on matching sites + toolbar popup |
| Sign-in | Device-code flow in the popup; Bearer token kept in `storage.local` |
| Link matching | Host match against provider `mainUrl` only (no alias or mirror list). No background `load()`: if the URL isn't a title, the action shows an error. |
| Same host, several providers | Use the most recently installed one (`installedAt`, the first install time, kept across updates) |
| Menu items | "Open in Cloudstream" (new tab at `/title`) + "Play on ▸" submenu (mpv + online cast devices) |
| Permissions | Request `<all_urls>` once at setup |
| Location | `extension/`, its own pnpm package, with a CI job and an `extension-v*` tag through `scripts/release.py` |
| Distribution | Chrome: load unpacked / CRX. Firefox: unlisted AMO-signed XPI on GitHub releases |

## User flow

1. **Popup, first run:** enter the server URL (for example `https://cloudstream.archer.istarvin.uk`). The extension requests `<all_urls>`, calls `GET /api/auth/status`, then starts `POST /api/auth/device?client=extension`. The popup shows the user code and a button that opens the verification URL. It polls `/api/auth/device/poll?client=extension` until the server returns `token`.
2. **Popup, signed in:** shows the server name, the connection state and the provider count. On a matching tab it also shows the matched provider with Open/Play buttons. It has "Sign out" and "Refresh providers".
3. **Right-click a link on a matching site:** "Open in Cloudstream" or "Play on ▸ mpv / <device>…".
4. **On any page of a matching site:** a small floating pill (shadow DOM, bottom-right) shows the provider icon and "Open in Cloudstream" with a ▾ play-on menu. The toolbar icon gets a badge.
5. **Non-title URL:** Open lands on the web UI's `/title` page, which shows the provider's load error, so improve that error state if it isn't clear. Play on returns the error from `/api/play-title`, and the extension shows it as a notification or inline in the pill.

## Matching

- `host(u)` is the lowercased hostname with a leading `www.` removed.
- Build a `host → provider` map from `/api/providers`. Where several providers share a host, keep the one with the largest `installedAt`. Providers with no `installedAt` (dev mode) rank last, in list order.
- A URL matches when its host equals a provider host or is a subdomain of one.
- Matching is purely client-side and costs nothing per page.

## Server changes (Kotlin)

1. **Extension origins pass the Origin check** (`Auth.isExtension`). A `chrome-extension://` or `moz-extension://` origin is accepted with a Bearer token or on the device sign-in endpoints (`/api/auth/status`, `/api/auth/device`, `/api/auth/device/poll`). A Bearer request with no `Origin` is also accepted: a web page can attach `Authorization` only in CORS mode, which always sends `Origin`. Chrome sends extension GETs without `Origin` (`Sec-Fetch-Site: none`) and POSTs with the extension origin, so both cases are needed. Other cross-origin requests, including Bearer ones from web origins, stay rejected.
2. **Issue the token to the extension.** In `AuthRoutes.complete`, return `token` for `client=extension` as it already does for `client=android`. Don't set a cookie for that client.
3. **`ProviderDto.installedAt: Long?`.** Fill it from `RepoSource.Installed.since`, the plugin's first install time (kept across updates and rollbacks, so a plugin update doesn't change which copy wins), and leave it null in dev mode. Mirror it in `web/src/lib/types.ts`.
4. **`POST /api/play-title { provider, url, device? }`** loads the details and picks the episode: resume the in-progress episode, otherwise the next unwatched one, otherwise the first. It then calls `playback.play` (mpv) or `cast.play(device, …)`. A load failure returns 4xx `{ error }` with a readable message, for example "Not a title page on MkvDrama". Put the episode choice in `shared/` so Android can reuse it. If the web UI already has equivalent logic, mirror it and add a case to `testdata/`.

## Extension layout

```
extension/
  package.json            # wxt, svelte, @wxt-dev/module-svelte, @lucide/svelte
  wxt.config.ts           # manifest, permissions, svelte module, alias $shared -> ../web/src/lib
  src/
    entrypoints/
      background.ts       # API client, provider cache, context menus, badge, message router
      popup/              # index.html + App.svelte (server URL, device sign-in, current-tab card)
      site.content/       # content script on <all_urls>: asks background "match(location.host)?", mounts the pill via createShadowRootUi
    lib/
      api.ts              # fetch wrapper: base URL + Bearer, 401 -> signed-out state
      storage.ts          # wxt storage items: serverUrl, token, providers (+fetchedAt), hiddenHosts
      match.ts            # host normalisation + host->provider map with installedAt tie-break (unit-tested)
      menus.ts            # builds/updates the context menu targetUrlPatterns from provider hosts
      theme.css           # tokens copied from DESIGN.md / web
    components/           # Pill.svelte, PlayOnMenu.svelte, ProviderCard.svelte, DeviceCode.svelte
```

**Background service worker**
- All network calls go here. Content scripts and the popup send it messages, so the token never enters page context. With `<all_urls>`, CORS doesn't apply to these requests.
- Provider list: fetched on startup, on sign-in, every 30 minutes (`alarms`), and from "Refresh". Stored with `fetchedAt`.
- Context menu: one parent menu with `contexts: ["link"]` and `targetUrlPatterns` built from the provider hosts (`*://<host>/*` and `*://*.<host>/*`), refreshed whenever the provider list changes.
  - Open: `tabs.create({ url: `${server}/title?provider=${encodeURIComponent(p)}&url=${encodeURIComponent(u)}` })`.
  - Play on: `POST /api/play-title`, then a notification with the result or error.
- "Play on ▸" children are rebuilt from `GET /api/cast/devices` when a menu opens (`menus.onShown` in Firefox) or on a short poll in Chrome, which has no `onShown`. mpv is always listed first.
- Badge: set per tab on `tabs.onUpdated` when the tab's host matches. No content-script round trip is needed.

**Content script** (`matches: ["<all_urls>"]`, exits immediately when the host doesn't match)
- Uses the current `location.href` when clicked, so SPA navigation needs no special handling.
- Mounts `Pill.svelte` in a shadow root so site CSS can't affect it and the pill can't affect the site. The pill can be dismissed, and a per-host "hide on this site" setting is saved in `hiddenHosts`. The popup shows when the button is hidden on the current site and can show it again.
- The pill can't tell title pages from other pages, so it appears on every page of a matching site and the error appears on click. Possible later improvement: show it only on links-free "detail-looking" pages. Not planned.

**Permissions:** `storage`, `contextMenus` (`menus` in Firefox), `alarms`, `notifications`, and `host_permissions: ["<all_urls>"]`. No `tabs`: host access already exposes tab URLs, and `tabs` would add a browsing-history install warning.

## UI / design

- Reuse the web UI's tokens (colours, radius, type) from `DESIGN.md`. Keep the pill small: about 40 px tall, with the provider icon, the label and a chevron.
- Lucide icons: `Play`, `ExternalLink`, `MonitorPlay` (mpv), `Cast`, `Tv` / `Smartphone` per device type, `LogOut`, `RefreshCw`, `Loader`, `CircleAlert`.

## Tooling and CI

- `extension/` uses pnpm with WXT's own CLI (`wxt`, `wxt build -b firefox`, `wxt zip`). Check whether `vp` lint/format can cover it. If it can't run alongside WXT's Vite, use the repo's oxlint/prettier config directly.
- Tests: Vitest with WXT's `fakeBrowser` for `match.ts` (www/subdomain/installedAt tie-break), `menus.ts` and the API client's 401 handling. Kotlin tests cover the auth changes, `installedAt` and play-title (success plus a load failure becoming 4xx).
- CI: a new job runs `pnpm install && wxt prepare && check && test && wxt zip` for both browsers.
- Release: extend `scripts/release.py` and the `cloudstream-release` skill with an `extension` component (`extension-v*` tag, notes in `release-notes/`). The workflow attaches the Chrome zip and the AMO-signed XPI (`web-ext sign --channel unlisted`, which needs AMO API secrets).

## Notes from the implementation

- **Firefox runs MV3 too** (`manifestVersion: 3`), so both browsers share `action` and the badge. Firefox treats `host_permissions` as optional, which is why the popup has an "Allow access to all sites" step. Chrome skips it.
- **Sign-in polling lives in the background.** A device code works only once, and the popup closes when the approval page opens. The popup follows storage changes.
- **First run** opens the popup as a tab, so granting access and signing in don't close it.
- **Play on ▸ in Chrome** uses the device list refreshed every minute (alarm), on popup open and when the page button's menu opens. Firefox refreshes it when the menu is shown (`menus.onShown`).
- **The web title page's error state** shows the provider and URL and has an "Open original page" link, for links that aren't titles.
- **`NextUp`** (shared/) and `web/src/lib/next-up.ts` share `testdata/next-up-cases.json`. The web title page now uses the shared module.
