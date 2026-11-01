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
  mpv_extra_args: string[];
  autoplay_best: boolean;
  autoplay_min_links: number;
  autoplay_wait_ms: number;
  autoplay_next: boolean;
  show_nsfw: boolean;
  disabled_plugins: string[];
  proxy_all: boolean;
  chromium_mode: BrowserMode;
}

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
