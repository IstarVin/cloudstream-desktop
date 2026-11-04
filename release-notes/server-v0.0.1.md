# Server 0.0.1

Initial release of the Cloudstream Desktop server and bundled web app.

- Run Cloudstream plugins on the desktop JVM from published repositories or a local extensions checkout, with plugin updates, rollback, and development hot reload.
- Browse provider feeds, search across providers, and view title details and episodes in the web app.
- Keep library lists, watch history, and playback progress together in SQLite for connected clients.
- Play through desktop mpv, the browser, or Android, with source ranking, automatic fallback, subtitle selection, next-episode playback, and AniSkip controls.
- Proxy HLS/DASH streams and subtitles, including supported ClearKey streams.
- Pair LAN clients and send playback between connected Cloudstream players.
- Install the web UI as a PWA; use optional Chromium for browser-dependent providers and Cloudflare challenges.
- Run the headless server and web UI in an amd64 Docker image with persistent configuration and data volumes.

## Requirements and limitations

- Pair this initial server release with Android 0.0.1 when using the native client.
- Desktop playback requires mpv 0.39 or later. The Docker image includes Chromium and has no mpv; use browser or Android playback.
- Plugin compatibility varies, including for converted Android plugins. Browser playback depends on supported codecs, and some provider challenges need interaction.
- PWA installation on another device requires HTTPS with a trusted certificate. The web app and all clients still need a reachable server.
- LAN access requires pairing. Keep port 6969 on a trusted network rather than exposing it directly to the internet.
