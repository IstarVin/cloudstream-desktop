# Server 0.0.4

## Changes

- Replace shared pairing tokens with account sign-in and individual, revocable device sessions. Manage sessions in Settings.
- Configure the container timezone and improve Chromium compatibility with sites using Cloudflare challenges.

## Upgrade

- Deploy and configure the authentication Worker with `CLOUDSTREAM_AUTH_URL` and `CLOUDSTREAM_AUTH_SECRET` before upgrading. All clients, including the local browser, must sign in. See `auth-worker/README.md` for setup.
- Previous pairing tokens and QR links no longer work. Update Android to 0.0.5 and sign in again.
