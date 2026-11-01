# Source priority profiles

Settings → Source priority and the Sources sheet offer seven named profiles. The active profile is saved on the
server and applies to every client, auto-play, fallback, subsequent episodes and mpv's source picker. Switching
profiles reorders the choices without interrupting a playing stream. Desktop uses an explicit active profile;
Android's Wi-Fi, mobile-data and download assignments do not apply to desktop playback.

A link's priority is its source score (default **1**) plus its nearest quality score. Defaults match Cloudstream:
Auto/unknown 4, 144p 0, 240p 2, 360p 3, 480p 4, 720p 5, 1080p 6, 1440p 7, 2160p 8. Source names match exactly.
The nearest quality uses enum order for equal distances, with Auto/unknown first. Unsupported streams remain
unplayable regardless of priority. Quality, source success history, then arrival order break equal-priority ties.

With auto-play enabled, a playable link with total priority **10 or higher** bypasses the usual link-count/timeout
wait. Resolution continues to collect other links and subtitles. Otherwise the existing wait settings apply.
Optional per-profile filters hide links that failed during this playback session or have negative total priority.
Hidden negative links are excluded from automatic selection. The current stream always remains visible, and
manual selection remains available. Failed links are never retried automatically within a session.

## Settings API

The existing authenticated `GET /api/settings` and `PUT /api/settings` include:

- `source_profile`: integer from 1 to 7, default 1.
- `source_profiles`: an array of exactly seven profiles, with each id from 1 to 7 appearing once.

Each profile has `id`, `name`, `sourcePriorities` (source-name → integer), `qualityPriorities` (quality-number string →
integer), `hideFailedSources` and `hideNegativeSources`. Missing maps default to empty; missing booleans default to
false. Omitted scores use the defaults above. Names must contain 1–80 non-whitespace characters after trimming;
source labels must be nonblank and at most 200 characters; scores must be integers from -1000 to 1000. Quality
keys must be one of 400, 144, 240, 360, 480, 720, 1080, 1440 or 2160. Invalid patches return 400 before any keys
are changed. Partial settings updates remain supported; editing profiles replaces the complete array.

Example selection: `PUT /api/settings` with `{"source_profile":2}`. Both operations return the full settings object.
Profiles persist in the existing SQLite settings table, including across server restarts. Link SSE payloads stay
unchanged; clients compute scores from the settings so profile changes also reorder links already received.
