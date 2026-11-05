# Authentication contract

One owner account lives in a central Cloudflare Worker. Its username and password are seeded once from `OWNER_USERNAME` and `OWNER_PASSWORD` secrets. The owner can change both at the Worker's site. There is no public registration. Every server configured with the Worker's service secret belongs to that owner.

Servers use `CLOUDSTREAM_AUTH_URL` (HTTPS Worker origin), `CLOUDSTREAM_AUTH_SECRET` (same value as the Worker's `SERVER_SECRET`, at least 32 characters), and optional `CLOUDSTREAM_SERVER_NAME`. A persistent random server ID binds device grants to the originating server. The Worker never connects to a client-supplied server address.

## Server API

JSON requests and responses, with `Cache-Control: no-store` on authentication endpoints. Browser POST/DELETE calls require a same-origin Origin when present; cross-site fetches and WebSockets are refused. No CORS is enabled. Android uses `Authorization: Bearer <session>`; web uses a host-only HttpOnly, SameSite=Strict cookie (Secure when the request or configured public origin is HTTPS).

| Endpoint | Authentication | Request / response |
| --- | --- | --- |
| GET /api/auth/status | Public | `{authed,loopback,configured,workerUrl,serverName}`; loopback is informational and never bypasses login |
| POST /api/auth/device | Public | `{deviceName}` → `{userCode,deviceCode,verificationUri,expiresIn:300,interval:5}` |
| POST /api/auth/device/poll | Public | `{deviceCode}` → `{status:"pending"}` or `{status:"approved",token,expiresAt}` and browser cookie |
| POST /api/auth/login | Public | `{username,password,deviceName}` → `{status:"approved",token,expiresAt}` and browser cookie |
| POST /api/auth/logout | Session | Revokes current session, clears cookie |
| GET /api/auth/sessions | Session | Array of `{id,name,createdAt,expiresAt,current}` |
| DELETE /api/auth/sessions/{id} | Session | Revokes that session |
| POST /api/auth/revoke-all | Session | Revokes all sessions on this server |

Login and poll accept `?client=android` to return the bearer token. Browser responses omit `token`. Sessions have a fixed 30-day lifetime, random 256-bit tokens stored only as SHA-256 hashes on the server, and persist across restarts. Expired sessions are pruned. Legacy `/pair`, `/api/auth/pairing`, and `/api/auth/rotate` no longer authenticate anything; the old shared token file is ignored.

Errors use `{error}`: 400 malformed request, 401 invalid credentials/session, 403 cross-origin request, 410 expired/consumed grant, 429 rate limit (poll after the supplied interval), 503 Worker unavailable or unconfigured. An uncertain exchange never creates a session: request a new code if a response was lost. Device secrets and session tokens never appear in URLs.

## Worker API

All routes are serialized by one SQLite-backed Durable Object. Device grants expire after five minutes, user codes use six uppercase characters from an unambiguous alphabet, and high-entropy device secrets are hashed at rest. Start, password, code lookup/approval, and polling are rate limited; active grants and browser sessions are bounded. There is no public code enumeration endpoint.

`POST /api/server/{device,poll,login,epoch}` requires `Authorization: Bearer <SERVER_SECRET>` and JSON `serverId`. Device creation also sends `serverName,deviceName`; login sends `username,password,clientIp`; poll sends `deviceCode`. Device approval is consumed atomically and is valid only for that server. Responses are the server API shapes, with `epoch` on approved/login/epoch responses.

The portal uses same-origin JSON `POST /api/login`, `POST /api/logout`, `GET /api/account`, `POST /api/device/lookup` (`userCode`), `POST /api/device/approve` (`userCode,approve`), `POST /api/account` (`username,password,currentPassword`), and `POST /api/revoke-all`. A hashed, random HttpOnly Secure SameSite=Strict cookie identifies a 12-hour portal session. Approval displays the server and device and requires an explicit second action. Rejecting a request consumes it.

Changing credentials or using the portal's global sign-out increments an account epoch and invalidates all pending grants and other portal sessions. Servers check the epoch at most every 60 seconds; if the Worker is unreachable, the last verified epoch is trusted for up to 24 hours so local playback keeps working, and after that (or before any successful check since startup) requests return 503. A revocation made at the Worker therefore takes effect on a server only once it can reach the Worker again. The server's own mpv process authenticates its loopback `/proxy/` and subtitle requests with a per-process secret rather than a user session. Per-device revocation on a server is immediate for subsequent requests. Already-open media responses and WebSockets are not forcibly interrupted; their next connection/request requires a valid session.

## Transport and deployment

Use HTTPS for publicly reachable servers. Device-code approval keeps the password on the Worker's HTTPS site when the server is on a trusted HTTP LAN. HTTP still exposes device/session secrets to network observers; it is not suitable for an untrusted network. Direct username/password entry is offered only on HTTPS or loopback server connections. For TLS termination, set `CLOUDSTREAM_PUBLIC_ORIGIN=https://your-server.example`; the server validates browser origins against it and marks cookies Secure. Do not trust arbitrary forwarded headers.

See [auth-worker/README.md](../auth-worker/README.md) for deployment and bootstrap/recovery. No credentials or deployments are included in source control.
