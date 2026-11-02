// Mirrors the server's DTOs (server/src/main/kotlin/desktop/api/Dto.kt and player/Playback.kt).

export interface Provider {
  name: string;
  plugin: string;
  mainUrl: string;
  lang: string;
  types: string[];
  nsfw: boolean;
  hasMainPage: boolean;
  iconUrl: string | null;
}

export interface PluginInfo {
  name: string;
  /** Icon declared by plugins.json or the local module metadata. */
  iconUrl?: string | null;
  version: number | null;
  description: string | null;
  status: "loaded" | "error" | "disabled" | "missing";
  error: string | null;
  nsfw: boolean;
  tvTypes: string[];
  enabled: boolean;
  providers: string[];
  lastUsed: number;
  settingsFiles: string[];
  /** References into the runtime that won't link, found when the plugin loaded. */
  linkage: string[];
  /** The first linkage error a call into the plugin hit. */
  runtimeLinkage?: string | null;
  /** Repo mode: the version the repo offers, when it publishes a desktop jar. */
  available?: number | null;
  /** Repo mode: the version a rollback would restore. */
  previous?: number | null;
  /** Labelled settings the plugin declares; the form shows any other keys below them. */
  settingsSchema?: SettingField[];
}

export interface SettingField {
  file: string | null;
  key: string;
  type: "string" | "secret" | "boolean" | "int" | "choice";
  label: string;
  help?: string | null;
  default?: string | null;
  options: { value: string; label: string }[];
}

export type PluginSourceMode = "dev" | "repo";

export interface PluginSource {
  mode: PluginSourceMode;
  devPath: string;
  devAvailable: boolean;
  repoUrl: string;
  repoName?: string | null;
  /** When the repo's index was last read, or 0. */
  checkedAt: number;
  error?: string | null;
  /** Installed plugins with a newer version in the repo. */
  updates: string[];
}

export interface SearchItem {
  provider: string;
  name: string;
  url: string;
  type: string | null;
  poster: string | null;
  posterHeaders?: Record<string, string>;
  quality?: string;
  year?: number;
  score?: number;
}

export interface HomeSection {
  index: number;
  name: string;
  horizontal: boolean;
}

export interface HomeRow {
  name: string;
  horizontal: boolean;
  items: SearchItem[];
}

export interface HomePage {
  rows: HomeRow[];
  hasNext: boolean;
}

export interface Progress {
  position: number;
  duration: number;
  watched: boolean;
  updated: number;
}

export interface Episode {
  data: string;
  name: string | null;
  season: number | null;
  episode: number | null;
  poster: string | null;
  description: string | null;
  date: number | null;
  runtime: number | null;
  dub?: string | null;
  progress?: Progress | null;
}

export interface Details {
  provider: string;
  url: string;
  name: string;
  type: string;
  kind: "movie" | "series" | "anime" | "live" | "torrent" | "other";
  poster: string | null;
  background: string | null;
  logo: string | null;
  plot: string | null;
  year: number | null;
  tags: string[];
  score: number | null;
  duration: number | null;
  contentRating: string | null;
  showStatus: string | null;
  actors: { name: string; image: string | null; role: string | null }[];
  trailers: string[];
  recommendations: SearchItem[];
  seasons: { season: number; name: string | null; displaySeason: number | null }[];
  episodes: Episode[];
  movieData: string | null;
  movieProgress?: Progress | null;
  nsfw: boolean;
  bookmarked?: string | null;
  malId?: number | null;
  aniListId?: number | null;
}

export interface Link {
  id: string;
  source: string;
  name: string;
  quality: number;
  qualityLabel: string;
  type: string;
  drm: boolean;
  playable: boolean;
  proxied: boolean;
  host: string;
  /** Arrival order within the episode's links. */
  order: number;
  /** The source's smoothed success ratio for this provider (0.5 when new). */
  score: number;
  successes: number;
  failures: number;
}

export interface Subtitle {
  id: string;
  lang: string;
  url: string;
}

export interface Track {
  id: number;
  type: string;
  title: string | null;
  lang: string | null;
  selected: boolean;
  external: boolean;
}

export interface PlayerState {
  status: "idle" | "resolving" | "playing" | "paused" | "error";
  message?: string | null;
  position: number;
  duration: number;
  paused: boolean;
  volume: number;
  title?: string | null;
  episodeLabel?: string | null;
  provider?: string | null;
  url?: string | null;
  data?: string | null;
  poster?: string | null;
  link?: Link | null;
  audioTracks: Track[];
  subTracks: Track[];
  hasNext: boolean;
  hasPrev: boolean;
  mpvRunning: boolean;
  /** The next episode's links, resolved in the background near the end. */
  next?: NextEpisode | null;
  /** Links of this item that failed to play this session. */
  failedLinks?: string[];
  /** The opening, ending or recap the position is in, while the player offers to skip it. */
  skip?: SkipRange | null;
}

export interface SkipRange {
  type: "op" | "ed" | "recap";
  start: number;
  end: number;
}

export interface NextEpisode {
  label: string | null;
  status: "waiting" | "resolving" | "ready" | "failed";
  /** Playable links found so far. */
  links: number;
}

export interface HistoryEntry {
  provider: string;
  url: string;
  title: string;
  poster: string | null;
  type: string | null;
  lastWatched: number;
}

export interface ContinueEntry {
  provider: string;
  url: string;
  title: string;
  poster: string | null;
  episodeData: string;
  episodeLabel: string | null;
  position: number;
  duration: number;
  updated: number;
}

export interface Bookmark {
  provider: string;
  url: string;
  title: string;
  poster: string | null;
  type: string | null;
  list: string;
  added: number;
}

export interface Settings {
  source_profile: number;
  source_profiles: SourceProfile[];
  mpv_extra_args: string[];
  autoplay_best: boolean;
  autoplay_min_links: number;
  autoplay_wait_ms: number;
  autoplay_next: boolean;
  show_nsfw: boolean;
  disabled_plugins: string[];
  proxy_all: boolean;
  skip_segments: SkipMode;
  chromium_mode: BrowserMode;
  plugin_source: PluginSourceMode;
  plugin_repo_url: string;
}

export interface SourceProfile {
  id: number;
  name: string;
  sourcePriorities: Record<string, number>;
  qualityPriorities: Record<string, number>;
  hideFailedSources: boolean;
  hideNegativeSources: boolean;
}

export type SkipMode = "prompt" | "auto" | "off";

export type BrowserMode = "headless" | "xvfb" | "headed";

/** A browser check (Cloudflare or a page) the background browser couldn't pass. */
export interface BrowserCheck {
  id: string;
  kind: "cloudflare" | "page";
  host: string;
  url: string;
  since: number;
  solving: boolean;
}

export interface BrowserStatus {
  running: boolean;
  mode: BrowserMode | null;
  configuredMode: BrowserMode;
  executable: string | null;
  userAgent: string | null;
  error: string | null;
  checks: BrowserCheck[];
  clearances: string[];
}

export interface PrefEntry {
  key: string;
  type: string;
  value: string | null;
  default?: string | null;
}

export interface Status {
  building: boolean;
  plugins: number;
  loaded: number;
  providers: number;
  mpvRunning: boolean;
  authed: boolean;
  loopback: boolean;
}

export const BOOKMARK_LISTS = ["watching", "planned", "completed", "dropped"] as const;
