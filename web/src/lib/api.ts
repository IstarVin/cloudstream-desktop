import type {
  Bookmark,
  BrowserStatus,
  ContinueEntry,
  Details,
  HistoryEntry,
  HomePage,
  HomeSection,
  Link,
  PlayerState,
  PluginInfo,
  PluginSource,
  PrefEntry,
  Provider,
  SearchItem,
  Settings,
  Status,
  Subtitle,
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(json?.error ?? res.statusText, res.status);
  return json as T;
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const q = (params: Record<string, string>) => new URLSearchParams(params).toString();

export const api = {
  authStatus: () => get<{ authed: boolean; loopback: boolean }>("/api/auth/status"),
  pairing: () => get<{ url: string; token: string; lanUrls: string[] }>("/api/auth/pairing"),
  rotateToken: () => post("/api/auth/rotate"),
  status: () => get<Status>("/api/status"),

  providers: () => get<Provider[]>("/api/providers"),
  homeSections: (provider: string) =>
    get<HomeSection[]>(`/api/providers/${encodeURIComponent(provider)}/home`),
  homeRow: (provider: string, section: number, page = 1) =>
    get<HomePage>(`/api/providers/${encodeURIComponent(provider)}/home/${section}?page=${page}`),
  details: (provider: string, url: string) => get<Details>(`/api/load?${q({ provider, url })}`),

  play: (body: {
    provider: string;
    url: string;
    data: string;
    linkId?: string;
    subtitleIds?: string[];
    startAt?: number;
  }) => post("/api/play", body),
  player: (cmd: string, body: Record<string, unknown> = {}) => post(`/api/player/${cmd}`, body),

  history: () => get<HistoryEntry[]>("/api/history"),
  deleteHistory: (provider: string, url: string) =>
    request("DELETE", `/api/history?${q({ provider, url })}`),
  clearHistory: () => request("DELETE", "/api/history/all"),
  continueWatching: () => get<ContinueEntry[]>("/api/continue"),
  setWatched: (provider: string, url: string, data: string, watched: boolean) =>
    post("/api/progress", { provider, url, data, watched }),
  bookmarks: () => get<Bookmark[]>("/api/bookmarks"),
  setBookmark: (b: {
    provider: string;
    url: string;
    title: string;
    poster: string | null;
    type: string;
    list: string | null;
  }) => post("/api/bookmarks", b),

  settings: () => get<Settings>("/api/settings"),
  saveSettings: (s: Partial<Settings>) => put<Settings>("/api/settings", s),
  plugins: () => get<PluginInfo[]>("/api/plugins"),
  rebuild: (modules?: string[]) => post("/api/plugins/rebuild", { modules }),
  buildLog: async () => (await fetch("/api/plugins/build-log")).text(),
  pluginSource: () => get<PluginSource>("/api/plugins/source"),
  checkUpdates: () => post<PluginSource>("/api/plugins/check-updates"),
  updatePlugins: (modules?: string[]) =>
    post<{ failed: Record<string, string>; source: PluginSource }>("/api/plugins/update", {
      modules,
    }),
  rollbackPlugin: (name: string) =>
    post<PluginSource>(`/api/plugins/${encodeURIComponent(name)}/rollback`),
  setPluginEnabled: (name: string, enabled: boolean) =>
    put(`/api/plugins/${encodeURIComponent(name)}/enabled`, { enabled }),
  pluginSettings: (name: string) =>
    get<{ files: Record<string, PrefEntry[]> }>(
      `/api/plugins/${encodeURIComponent(name)}/settings`,
    ),
  browser: () => get<BrowserStatus>("/api/browser"),
  solveCheck: (id: string) =>
    post<BrowserStatus>(`/api/browser/checks/${encodeURIComponent(id)}/solve`),
  dismissCheck: (id: string) =>
    request<BrowserStatus>("DELETE", `/api/browser/checks/${encodeURIComponent(id)}`),
  stopBrowser: () => post<BrowserStatus>("/api/browser/stop"),
  forgetClearance: (host: string) =>
    request<BrowserStatus>("DELETE", `/api/browser/clearances?${q({ host })}`),
  savePluginSettings: (name: string, files: Record<string, PrefEntry[]>) =>
    put<{ files: Record<string, PrefEntry[]> }>(
      `/api/plugins/${encodeURIComponent(name)}/settings`,
      { files },
    ),
};

/** Streams search results provider by provider. Returns a function that stops the stream. */
export function streamSearch(
  query: string,
  handlers: {
    results: (provider: string, items: SearchItem[]) => void;
    error: (provider: string, error: string) => void;
    done: () => void;
  },
  provider?: string,
): () => void {
  const es = new EventSource(
    `/api/search?${q(provider ? { q: query, providers: provider } : { q: query })}`,
  );
  es.addEventListener("results", (e) => {
    const d = JSON.parse((e as MessageEvent).data);
    handlers.results(d.provider, d.items);
  });
  es.addEventListener("error", (e) => {
    const data = (e as MessageEvent).data;
    if (data) {
      const d = JSON.parse(data);
      handlers.error(d.provider, d.error);
    } else {
      es.close();
      handlers.done();
    }
  });
  es.addEventListener("done", () => {
    es.close();
    handlers.done();
  });
  return () => es.close();
}

/** Streams the links and subtitles of one episode as the provider finds them. */
export function streamLinks(
  provider: string,
  data: string,
  handlers: {
    link: (l: Link) => void;
    subtitle: (s: Subtitle) => void;
    error: (message: string) => void;
    done: () => void;
  },
): () => void {
  const es = new EventSource(`/api/links?${q({ provider, data })}`);
  es.addEventListener("link", (e) => handlers.link(JSON.parse((e as MessageEvent).data)));
  es.addEventListener("subtitle", (e) => handlers.subtitle(JSON.parse((e as MessageEvent).data)));
  es.addEventListener("error", (e) => {
    const data = (e as MessageEvent).data;
    if (data) handlers.error(JSON.parse(data).error);
    else {
      es.close();
      handlers.done();
    }
  });
  es.addEventListener("done", () => {
    es.close();
    handlers.done();
  });
  return () => es.close();
}

export function playerSocket(
  onState: (s: PlayerState) => void,
  onOpen: (open: boolean) => void,
): () => void {
  let ws: WebSocket | null = null;
  let stopped = false;
  let retry: ReturnType<typeof setTimeout> | undefined;
  const connect = () => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/api/player/events`);
    ws.onopen = () => onOpen(true);
    ws.onmessage = (e) => onState(JSON.parse(e.data));
    ws.onclose = () => {
      onOpen(false);
      if (!stopped) retry = setTimeout(connect, 1500);
    };
  };
  connect();
  return () => {
    stopped = true;
    clearTimeout(retry);
    ws?.close();
  };
}

/** Route to a title's details page. URLs are long, so they go in the query string. */
export const titleHref = (provider: string, url: string) => `/title?${q({ provider, url })}`;

export function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) seconds = 0;
  const s = Math.floor(seconds % 60);
  const m = Math.floor((seconds / 60) % 60);
  const h = Math.floor(seconds / 3600);
  const mm = h ? String(m).padStart(2, "0") : String(m);
  return `${h ? h + ":" : ""}${mm}:${String(s).padStart(2, "0")}`;
}

export function episodeLabel(
  e: { season: number | null; episode: number | null; name: string | null },
  index: number,
) {
  const num = [
    e.season != null ? `S${e.season}` : "",
    e.episode != null ? `E${e.episode}` : "",
  ].join("");
  return [num, e.name ?? ""].filter(Boolean).join(" · ") || `Episode ${index + 1}`;
}
