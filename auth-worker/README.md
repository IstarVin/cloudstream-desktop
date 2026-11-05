# Cloudstream authentication Worker

One central Worker handles the owner account, device approvals, and account-wide sign-out. A SQLite-backed Durable Object stores the salted password hash, hashed portal sessions, rate limits, and temporary device grants. There is no registration.

## Deploy

Use Node 22 or newer and a Cloudflare account with Workers Paid enabled. The password hashing work needs more CPU than the Free plan's per-request budget. The checked-in Wrangler configuration allows up to 30 seconds of CPU; normal requests should take far less.

```sh
cd auth-worker
npm ci
npx wrangler login
npx wrangler secret put OWNER_USERNAME
npx wrangler secret put OWNER_PASSWORD
npx wrangler secret put SERVER_SECRET
npm run deploy
```

Enter an owner username and a unique password of at least 12 characters. Generate the service secret with `openssl rand -hex 32`; enter it as `SERVER_SECRET` and configure the same value on your server. Do not use the owner password as the service secret.

Open the deployed HTTPS URL and sign in once to initialize the account. The bootstrap secrets are read only when the account does not exist; changing them later does not overwrite credentials saved on the site. Use **Account settings → Change your sign-in** to change the username or password. Changes sign out devices on all configured servers within one minute. **Sign out all devices** does the same without changing credentials.

Only give the service secret to your own trusted Cloudstream servers: possession of it lets a server verify owner passwords and request approvals for this account. For a future multi-owner service, replace this shared service credential with explicit server enrollment and owner binding.

## Configure each server

```sh
export CLOUDSTREAM_AUTH_URL=https://cloudstream-auth.YOUR-SUBDOMAIN.workers.dev
export CLOUDSTREAM_AUTH_SECRET='the-same-random-service-secret'
export CLOUDSTREAM_SERVER_NAME='Home server'
# For an HTTPS reverse proxy terminating TLS:
export CLOUDSTREAM_PUBLIC_ORIGIN=https://cloudstream.example.com
```

`CLOUDSTREAM_PUBLIC_ORIGIN` is optional for a trusted HTTP LAN or direct HTTPS. Set it for TLS termination so browser cookies are Secure and Origin checks use the public address. Forward WebSocket upgrades and preserve the original Host; arbitrary forwarded headers are not trusted. All clients, including the local desktop browser, require sign-in. A missing Worker configuration leaves the server locked and shows setup instructions.

For Docker Compose, put the values in the repository's `.env` using `.env.example` as a template. Restart the server after setting them. For `docker run`, pass them using `--env-file .env`.

Devices get a six-character code valid for five minutes, a private polling secret, and an activation URL. After signing in at that URL, enter the code, check the server/device names, then approve. TV defaults to this flow. Direct username/password entry is available on HTTPS and localhost server connections. A trusted HTTP LAN uses device-code sign-in; the password is entered only on the Worker's HTTPS site.

Sessions last 30 days. Web uses a host-only HttpOnly cookie; Android uses a bearer token encrypted by Android Keystore on disk. The server keeps only token hashes. Web **Settings → Account & devices** can revoke individual sessions or every session on that server. Sign out from Android settings to revoke the Android session (if the server is reachable).

## Local checks

```sh
npm run check
npm test
npx wrangler deploy --dry-run
```

The tests run in Cloudflare's local Workers runtime and exercise approval, replay prevention, expiry, server binding, rejection, credential changes, and rate limits. To manually run the portal locally, copy `.dev.vars.example` to `.dev.vars`, replace its example values, and run `npm run dev`. The portal cookie is Secure; use HTTPS for a browser session. Production server configuration deliberately requires an HTTPS Worker origin.

## Operations

Back up the server data volume: it contains the server identity and hashed sessions. Copying a data volume also copies its sessions and server identity, so do not share it between separate servers. Existing shared pairing tokens and QR links are no longer accepted after upgrading; every client must sign in again.

Once initialized, bootstrap username/password secrets can be removed with `wrangler secret delete OWNER_USERNAME` and `wrangler secret delete OWNER_PASSWORD`. This reduces retained copies of the initial password. Keep the account password in your password manager. If every portal session and the password are lost, recovery requires an operator migration of the owner record with an incremented epoch, or a fresh Durable Object namespace **and deletion of `sessions.json` on every server before restarting**. Resetting the owner epoch to an old value while keeping server sessions can revive revoked credentials.

The Worker is required for sign-in and periodic account-epoch checks. Servers cache successful checks for at most 60 seconds, then return 503 during a Worker outage. Individual revocation takes effect on subsequent server requests; already-open streams and WebSocket connections are not forcibly closed.

The complete request and error contract is in [docs/authentication.md](../docs/authentication.md).
