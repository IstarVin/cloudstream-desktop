# Authentication contract

Every client, including a browser on the server's own computer, signs in and gets its own revocable session. Where the owner's credentials and device approvals live depends on the mode:

- **Local mode (default).** The server signs devices in itself. There is no username; the owner may set an optional password. New devices are approved from a device that is already signed in.
- **Worker mode.** Set `CLOUDSTREAM_AUTH_URL` (HTTPS Worker origin) and `CLOUDSTREAM_AUTH_SECRET` (the Worker's `SERVER_SECRET`, at least 32 characters). One owner account lives in a central Cloudflare Worker, shared by every server that holds the secret, with account-wide sign-out. See [auth-worker/README.md](../auth-worker/README.md).

The mode is stored with the sessions. Switching modes revokes every session; otherwise a session revoked by a Worker epoch bump would become valid again in local mode. `CLOUDSTREAM_SERVER_NAME` (optional) names the server on approval screens.

## Sessions

Sessions have a fixed 30-day lifetime. Tokens are random 256-bit values stored only as SHA-256 hashes in `sessions.json` (`{mode, sessions}`; a bare list from older versions loads as Worker mode), persisted across restarts, with expired sessions pruned and at most 100 kept. Android and the extension use `Authorization: Bearer <session>`; the web UI uses a host-only HttpOnly, SameSite=Strict cookie (Secure when the request or configured public origin is HTTPS). Per-device revocation is immediate for subsequent requests. Already-open media responses and WebSockets are not forcibly interrupted; their next connection or request needs a valid session.

The server's own mpv process authenticates its loopback `/proxy/` and subtitle requests with a per-process secret rather than a user session.

## Server API

JSON requests and responses, with `Cache-Control: no-store` on authentication endpoints. Browser POST/PUT/DELETE calls require a same-origin Origin when present; cross-site fetches and WebSockets are refused. No CORS is enabled. Request bodies are limited to 4 KiB, and each address may make 60 authentication requests a minute.

| Endpoint | Authentication | Mode | Request / response |
| --- | --- | --- | --- |
| GET /api/auth/status | Public | Both | `{authed,loopback,configured,workerUrl,serverName,mode,passwordSet,claimable}`; loopback is informational and never bypasses login |
| POST /api/auth/device | Public | Both | `{deviceName}` → `{userCode,deviceCode,verificationUri,expiresIn:300,interval:5}` |
| POST /api/auth/device/poll | Public | Both | `{deviceCode}` → `{status:"pending"}` or `{status:"approved",token,expiresAt}` and browser cookie |
| POST /api/auth/login | Public | Both | `{username,password,deviceName}` (no `username` in local mode) → `{status:"approved",token,expiresAt}` and browser cookie |
| POST /api/auth/claim | Public | Local | `{code,deviceName}` → as login |
| POST /api/auth/device/lookup | Session | Local | `{userCode}` → `{deviceName,address,requestedAt}` |
| POST /api/auth/device/approve | Session | Local | `{userCode,approve:boolean}` → `{ok}` |
| PUT /api/auth/password | Session | Local | `{password,currentPassword?}` (current required once a password is set) → `{ok}`; revokes every other session |
| POST /api/auth/logout | Session | Both | Revokes the current session, clears the cookie |
| GET /api/auth/sessions | Session | Both | Array of `{id,name,createdAt,expiresAt,current}` |
| DELETE /api/auth/sessions/{id} | Session | Both | Revokes that session |
| POST /api/auth/revoke-all | Session | Both | Revokes all sessions on this server |

Login, poll and claim accept `?client=android` (Bearer token in the body plus cookie) or `?client=extension` (Bearer token, no cookie). Browser responses omit `token`. Local-only endpoints return 404 in Worker mode. Direct password login is accepted only when the server's origin is HTTPS or loopback; on a plain-HTTP LAN, use device-code sign-in. Device secrets and session tokens never appear in URLs.

Errors use `{error}`: 400 malformed request, 401 invalid credentials or session, 403 cross-origin request, 404 endpoint not available in this mode, 410 expired, declined or consumed grant or claim link, 429 rate limit (poll after the supplied interval), 503 Worker unavailable or unconfigured. An uncertain exchange never creates a session: request a new code if a response was lost.

## Local mode

**Claim link.** While no password is set and no session is valid, the server logs a one-time link, `<origin>/#claim=<256-bit code>`, at startup and after the last session is revoked. The code sits in the URL fragment, which browsers never send, so it stays out of request logs and Referer headers; the web UI removes it from the address bar and posts it to `/api/auth/claim`. It is valid for one hour and one use, and each new link replaces the previous one. Restarting the server prints a fresh link, which is also the recovery path: revoke every session (or delete `sessions.json`) and restart. Anyone who can read the server log can sign in this way, so treat the log like the host itself.

**Device codes.** Grants live in memory and are lost on restart. They expire after five minutes; user codes are six characters from an unambiguous alphabet; device secrets are stored only as hashes; polling faster than every 4.5 seconds returns 429; at most 5 pending grants per address and 50 in total. `verificationUri` is `<server origin>/activate`. Lookup and approval need a session and are limited to 10 per session per 5 minutes. The approver sees the device name, the requesting address and the time. Each grant is decided once; declining consumes it, and an approval is consumed by the first successful poll.

**Password.** Optional, 12–256 characters, hashed with scrypt (N=2^15, r=8, p=1, random salt) in `owner.json` with owner-only file permissions. Password checks are limited to 10 per address and 60 overall per 15 minutes. Setting or changing it signs out every other device.

## Worker mode

Device approval keeps the password on the Worker's HTTPS site even when the server is on a plain-HTTP LAN. A persistent random server ID (`server-id`) binds device grants to the originating server. The Worker never connects to a client-supplied server address.

All Worker routes are serialized by one SQLite-backed Durable Object. Device grants expire after five minutes, user codes use six uppercase characters from an unambiguous alphabet, and high-entropy device secrets are hashed at rest. Start, password, code lookup/approval and polling are rate limited; active grants and browser sessions are bounded. There is no public code enumeration endpoint.

`POST /api/server/{device,poll,login,epoch}` requires `Authorization: Bearer <SERVER_SECRET>` and JSON `serverId`. Device creation also sends `serverName,deviceName`; login sends `username,password,clientIp`; poll sends `deviceCode`. Device approval is consumed atomically and is valid only for that server. Responses are the server API shapes, with `epoch` on approved/login/epoch responses.

The portal uses same-origin JSON `POST /api/login`, `POST /api/logout`, `GET /api/account`, `POST /api/device/lookup` (`userCode`), `POST /api/device/approve` (`userCode,approve`), `POST /api/account` (`username,password,currentPassword`), and `POST /api/revoke-all`. A hashed, random HttpOnly Secure SameSite=Strict cookie identifies a 12-hour portal session. Approval displays the server and device and requires an explicit second action. Rejecting a request consumes it.

Changing credentials or using the portal's global sign-out increments an account epoch and invalidates all pending grants and other portal sessions. Servers check the epoch at most every 60 seconds; if the Worker is unreachable, the last verified epoch is trusted for up to 24 hours so local playback keeps working, and after that (or before any successful check since startup) requests return 503. A revocation made at the Worker therefore takes effect on a server only once it can reach the Worker again.

## Transport and deployment

Use HTTPS for publicly reachable servers. HTTP exposes device and session secrets to network observers; it is suitable only on a trusted LAN. For TLS termination, set `CLOUDSTREAM_PUBLIC_ORIGIN=https://your-server.example`; the server validates browser origins against it, marks cookies Secure, and uses it for the claim link. Arbitrary forwarded headers are not trusted, so behind a reverse proxy every client shares the proxy's address for rate limits.

No credentials or deployments are included in source control.
