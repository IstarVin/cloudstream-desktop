import { castSocket } from "./api";
import { sources } from "./sources.svelte";
import type {
  CastFrame,
  DeviceInfo,
  PlayerCommand,
  PlayerState,
  PlayRequest,
  Track,
} from "./types";
import { webPlayer } from "./web-player.svelte";

const ID_KEY = "cast-device-id";
const NAME_KEY = "cast-device-name";
const RECEIVER_KEY = "cast-receiver";

/** Subtitle track ids in this browser's state: side-loaded ones count from 1, embedded ones from here. */
const EMBEDDED_SUB_BASE = 1000;

function stored(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function store(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Applies until the page reloads.
  }
}

function deviceId(): string {
  let id = stored(ID_KEY);
  if (!id) {
    id =
      globalThis.crypto?.randomUUID?.() ??
      `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    store(ID_KEY, id);
  }
  return id;
}

/** "Firefox on Linux" and the like, until the user names the device. */
export function defaultName(ua: string): string {
  const browser = /Edg\//.test(ua)
    ? "Edge"
    : /Firefox\//.test(ua)
      ? "Firefox"
      : /Chrome\//.test(ua)
        ? "Chrome"
        : /Safari\//.test(ua)
          ? "Safari"
          : "Browser";
  const os = /Android/.test(ua)
    ? "Android"
    : /iPhone|iPad/.test(ua)
      ? "iOS"
      : /Windows/.test(ua)
        ? "Windows"
        : /Mac OS X/.test(ua)
          ? "macOS"
          : /Linux|CrOS/.test(ua)
            ? "Linux"
            : null;
  return os ? `${browser} on ${os}` : browser;
}

/** What this browser's player reports to the devices controlling it. */
export function stateOf(p: typeof webPlayer, volume: number): PlayerState {
  if (!p.open || !p.item)
    return {
      status: "idle",
      position: 0,
      duration: 0,
      paused: true,
      volume,
      audioTracks: [],
      subTracks: [],
      hasNext: false,
      hasPrev: false,
      mpvRunning: false,
    };
  const d = p.item.details;
  const status =
    p.status === "resolving" || p.status === "loading"
      ? "resolving"
      : p.status === "error"
        ? "error"
        : p.paused || p.status === "ended"
          ? "paused"
          : "playing";
  const track = (id: number, type: string, title: string, selected: boolean, external: boolean) =>
    ({ id, type, title, lang: null, selected, external }) satisfies Track;
  return {
    status,
    message: p.message,
    position: p.position,
    duration: p.duration,
    paused: p.paused,
    volume,
    title: d.name,
    episodeLabel: p.label,
    provider: d.provider,
    url: d.url,
    data: p.item.data,
    poster: p.episode?.poster ?? d.poster,
    link: p.current,
    audioTracks: p.audioTracks.map((a) => track(a.id, "audio", a.label, a.selected, false)),
    subTracks: [
      ...p.loadedSubs.map((s, i) => track(i + 1, "sub", s.lang, p.subtitle === s.id, true)),
      ...p.embeddedSubs.map((s) =>
        track(EMBEDDED_SUB_BASE + s.id, "sub", s.label, p.embeddedSub === s.id, false),
      ),
    ],
    hasNext: p.hasNext,
    hasPrev: p.hasPrev,
    mpvRunning: false,
    failedLinks: p.failed,
    skip: p.skip,
  };
}

/**
 * This browser as a cast device: one socket to the server that lists the other devices and their players, takes
 * casts to play here (when allowed), and reports this player's state back.
 */
class Cast {
  readonly id = deviceId();
  name = $state(stored(NAME_KEY) ?? defaultName(globalThis.navigator?.userAgent ?? ""));
  /** Whether other devices may play here. */
  receiver = $state(stored(RECEIVER_KEY) !== "false");
  connected = $state(false);
  devices = $state<DeviceInfo[]>([]);
  /** The latest state of every other device's player, by device id. */
  states = $state<Record<string, PlayerState>>({});
  /** A cast arrived before the page had a user gesture, so the browser won't start it with sound. */
  needsTap = $state(false);

  private socket: ReturnType<typeof castSocket> | null = null;
  private reporter: ReturnType<typeof setInterval> | undefined;
  private lastSent = "";
  /** The page has had a click or key press, so media may play with sound. */
  private activated = !!globalThis.navigator?.userActivation?.hasBeenActive;

  /** Other devices, mpv first (the server's order). */
  get others() {
    return this.devices.filter((d) => d.id !== this.id);
  }

  /** Devices this one can cast to. */
  get targets() {
    return this.others.filter((d) => d.receiver);
  }

  device(id: string | null | undefined) {
    return id ? this.devices.find((d) => d.id === id) : undefined;
  }

  start() {
    if (this.socket) return;
    if (typeof document !== "undefined") {
      const activate = () => {
        this.activated = true;
        this.needsTap = false;
      };
      document.addEventListener("pointerdown", activate, { once: true, capture: true });
      document.addEventListener("keydown", activate, { once: true, capture: true });
    }
    this.socket = castSocket(
      () => this.hello(),
      (f) => this.onFrame(f),
      (open) => {
        this.connected = open;
        // The server forgets a device's state when it drops; send it again.
        this.lastSent = "";
      },
    );
    this.reporter = setInterval(() => this.report(), 500);
  }

  stop() {
    this.socket?.stop();
    this.socket = null;
    clearInterval(this.reporter);
  }

  rename(name: string) {
    this.name = name.trim() || defaultName(navigator.userAgent);
    store(NAME_KEY, this.name);
    this.socket?.send(this.hello());
  }

  setReceiver(on: boolean) {
    this.receiver = on;
    store(RECEIVER_KEY, String(on));
    this.socket?.send(this.hello());
  }

  private hello(): CastFrame {
    return { type: "hello", id: this.id, name: this.name, kind: "web", receiver: this.receiver };
  }

  onFrame(f: CastFrame) {
    switch (f.type) {
      case "devices": {
        this.devices = f.devices;
        const ids = new Set(f.devices.map((d) => d.id));
        for (const id of Object.keys(this.states)) if (!ids.has(id)) delete this.states[id];
        break;
      }
      case "state":
        if (f.device && f.device !== this.id) this.states[f.device] = f.state;
        break;
      case "play":
        if (this.receiver) void this.play(f.request);
        break;
      case "command":
        if (this.receiver) this.command(f.cmd, f.body);
        break;
    }
  }

  // ---- receiving ---------------------------------------------------------------------------------------------

  private async play(req: PlayRequest) {
    await webPlayer.start(
      { provider: req.provider, url: req.url, data: req.data },
      req.startAt ?? undefined,
      undefined,
      req.subtitleIds ?? [],
      req.linkId ?? undefined,
    );
    if (!this.activated) this.needsTap = true;
  }

  /** The tap the browser wants before playing with sound. */
  tapToPlay() {
    this.activated = true;
    this.needsTap = false;
    void webPlayer.video?.play();
  }

  /** A command from a controlling device, with the meaning it has for the server's mpv. */
  command(cmd: string, body: PlayerCommand) {
    const p = webPlayer;
    const v = p.video;
    switch (cmd) {
      case "toggle":
        p.toggle();
        break;
      case "pause":
        v?.pause();
        break;
      case "resume":
        void v?.play();
        break;
      case "seek":
        if (body.relative) p.seekBy(body.seconds ?? 0);
        else p.seekTo(body.seconds ?? 0);
        break;
      case "volume":
        if (v) v.volume = Math.max(0, Math.min(1, (body.value ?? 100) / 100));
        break;
      case "stop":
        p.close();
        break;
      case "next":
      case "watched-next":
        p.next();
        break;
      case "prev":
        p.prev();
        break;
      case "episode":
        if (body.value != null) p.episodeAt(Math.trunc(body.value));
        break;
      case "skip":
        p.skipNow();
        break;
      case "track":
        this.track(body.type, body.id);
        break;
      case "source": {
        const link = sources.links.find((l) => l.id === body.linkId);
        if (link) p.pick(link);
        break;
      }
      case "sub-add": {
        const sub = sources.subtitles.find((s) => s.id === body.id);
        if (sub) void p.selectSubtitle(sub);
        break;
      }
    }
  }

  private track(type: string | undefined, id: string | undefined) {
    const p = webPlayer;
    const n = Number(id);
    if (type === "audio") {
      if (Number.isFinite(n)) p.setAudio(n);
    } else if (type === "sub") {
      if (id == null || id === "no" || !Number.isFinite(n)) p.subtitlesOffNow();
      else if (n >= EMBEDDED_SUB_BASE) p.selectEmbedded(n - EMBEDDED_SUB_BASE);
      else {
        const s = p.loadedSubs[n - 1];
        if (s) void p.selectSubtitle({ id: s.id, lang: s.lang, url: "" });
      }
    }
  }

  /** Sends this player's state when it changed; position moves it on about twice a second while playing. */
  private report() {
    if (!this.receiver) return;
    const state = stateOf(webPlayer, Math.round((webPlayer.video?.volume ?? 1) * 100));
    const json = JSON.stringify(state);
    if (json === this.lastSent) return;
    this.lastSent = json;
    this.socket?.send({ type: "state", state });
  }
}

export const cast = new Cast();
