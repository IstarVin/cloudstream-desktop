import type { EmbeddedTrack, Stream } from "../types";
import { clearKeyHls } from "./hls-clearkey";
import { watchMkvSubs } from "./mkv-subs";

export interface AudioTrack {
  id: number;
  label: string;
  selected: boolean;
}

/** A rendition of an adaptive stream; quality is otherwise chosen by link, as on the phone. */
export interface Level {
  id: number;
  height: number;
  label: string;
}

/** A subtitle track inside the stream itself (HLS/DASH renditions, or muxed into an MKV). */
export interface EmbeddedSub {
  id: number;
  label: string;
  /** Language and flags, known for MKV tracks. */
  lang?: string;
  default?: boolean;
  forced?: boolean;
}

export interface EngineCallbacks {
  /** The stream can't play: the session falls back to the next link. */
  onFatal: (reason: string) => void;
  /** Audio tracks, levels or embedded subtitles changed. */
  onTracks: () => void;
}

/** One stream attached to the `<video>`, whatever plays it: hls.js, dash.js or the browser itself. */
export interface Engine {
  readonly kind: "hls" | "dash" | "native";
  audioTracks(): AudioTrack[];
  setAudio(id: number): void;
  levels(): Level[];
  /** The chosen level, or -1 for automatic. */
  level(): number;
  setLevel(id: number): void;
  embeddedSubs(): EmbeddedSub[];
  /** Shows an embedded subtitle, or none with -1. */
  setEmbeddedSub(id: number): void;
  /** The embedded subtitle's text track when the player draws its cues itself (MKV text); null otherwise. */
  embeddedTextTrack?(): TextTrack | null;
  destroy(): void;
}

const hexToB64Url = (hex: string) =>
  btoa(String.fromCharCode(...(hex.match(/../g) ?? []).map((h) => parseInt(h, 16))))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

/** A ClearKey license answered locally, like the phone's `ClearKeyCallback`. */
function clearKeyLicense(kidHex: string, keyHex: string) {
  return JSON.stringify({
    keys: [{ kty: "oct", kid: hexToB64Url(kidHex), k: hexToB64Url(keyHex) }],
    type: "temporary",
  });
}

const trackLabel = (lang?: string | null, name?: string | null, fallback = "Track") =>
  [lang?.toUpperCase(), name].filter(Boolean).join(" · ") || fallback;

export async function attach(
  video: HTMLVideoElement,
  stream: Stream,
  startAt: number,
  cb: EngineCallbacks,
): Promise<Engine> {
  if (stream.type === "M3U8") {
    const engine = await attachHls(video, stream, startAt, cb);
    if (engine) return engine;
  }
  if (stream.type === "DASH") return attachDash(video, stream, startAt, cb);
  // A plain file through the proxy may be an MKV, whose own subtitles the server reads out for the browser.
  return attachNative(video, stream.url, startAt, cb, stream.proxied && stream.type === "VIDEO");
}

/** Playback errors from the `<video>` itself; hls.js and dash.js report their own. */
function videoErrors(video: HTMLVideoElement, cb: EngineCallbacks) {
  const onError = () => {
    const e = video.error;
    if (!e) return;
    const reason =
      e.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED
        ? "This browser can't play this format"
        : e.message || `Media error ${e.code}`;
    cb.onFatal(reason);
  };
  video.addEventListener("error", onError);
  return () => video.removeEventListener("error", onError);
}

const mkvLabel = (t: EmbeddedTrack, i: number) =>
  [trackLabel(t.lang, t.name, `Subtitle ${i + 1}`), t.forced && "(forced)"]
    .filter(Boolean)
    .join(" ");

function attachNative(
  video: HTMLVideoElement,
  url: string,
  startAt: number,
  cb: EngineCallbacks,
  embedded = false,
): Engine {
  const off = videoErrors(video, cb);
  const mkv = embedded ? watchMkvSubs(video, url, cb.onTracks, mkvLabel) : null;
  const seek = () => {
    if (startAt > 0) video.currentTime = startAt;
  };
  video.addEventListener("loadedmetadata", seek, { once: true });
  video.src = url;
  return {
    kind: "native",
    audioTracks: () => [],
    setAudio: () => {},
    levels: () => [],
    level: () => -1,
    setLevel: () => {},
    embeddedSubs: () =>
      (mkv?.tracks() ?? []).map((t, i) => ({
        id: i,
        label: mkvLabel(t, i),
        lang: t.lang ?? undefined,
        default: t.default,
        forced: t.forced,
      })),
    setEmbeddedSub: (id) => mkv?.select(id),
    embeddedTextTrack: () => mkv?.textTrack() ?? null,
    destroy() {
      off();
      mkv?.destroy();
      video.removeEventListener("loadedmetadata", seek);
      video.removeAttribute("src");
      video.load();
    },
  };
}

async function attachHls(
  video: HTMLVideoElement,
  stream: Stream,
  startAt: number,
  cb: EngineCallbacks,
): Promise<Engine | null> {
  const { default: Hls } = await import("hls.js");
  if (!Hls.isSupported()) {
    // Safari plays HLS itself.
    return video.canPlayType("application/vnd.apple.mpegurl")
      ? attachNative(video, stream.url, startAt, cb)
      : null;
  }
  const key = stream.clearKey;
  const hls = new Hls({
    ...(key?.kid ? {} : clearKeyHls(Hls)),
    startPosition: startAt > 0 ? startAt : -1,
    backBufferLength: 30,
    maxBufferLength: 60,
    maxMaxBufferLength: 120,
    ...(key?.kid
      ? {
          emeEnabled: true,
          drmSystems: {
            "org.w3.clearkey": {
              licenseUrl: `data:application/json,${encodeURIComponent(clearKeyLicense(key.kid, key.key))}`,
            },
          },
        }
      : {}),
  });
  let recovered = false;
  hls.on(Hls.Events.ERROR, (_, data) => {
    if (!data.fatal) return;
    // A decode hiccup is often recoverable once; anything else moves on to the next link.
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) {
      recovered = true;
      hls.recoverMediaError();
      return;
    }
    cb.onFatal(data.details);
  });
  for (const ev of [
    Hls.Events.MANIFEST_PARSED,
    Hls.Events.AUDIO_TRACKS_UPDATED,
    Hls.Events.AUDIO_TRACK_SWITCHED,
    Hls.Events.LEVEL_SWITCHED,
    Hls.Events.SUBTITLE_TRACKS_UPDATED,
    Hls.Events.SUBTITLE_TRACK_SWITCH,
  ])
    hls.on(ev, () => cb.onTracks());
  hls.subtitleDisplay = false;
  hls.subtitleTrack = -1;
  hls.loadSource(stream.url);
  hls.attachMedia(video);
  let chosen = -1;
  return {
    kind: "hls",
    audioTracks: () =>
      hls.audioTracks.map((t, i) => ({
        id: i,
        label: trackLabel(t.lang, t.name, `Audio ${i + 1}`),
        selected: i === hls.audioTrack,
      })),
    setAudio: (id) => (hls.audioTrack = id),
    levels: () =>
      hls.levels
        .map((l, i) => ({
          id: i,
          height: l.height,
          label: l.height ? `${l.height}p` : `${Math.round(l.bitrate / 1000)} kbps`,
        }))
        .sort((a, b) => b.height - a.height),
    level: () => chosen,
    setLevel(id) {
      chosen = id;
      hls.nextLevel = id;
      cb.onTracks();
    },
    embeddedSubs: () =>
      hls.subtitleTracks.map((t, i) => ({
        id: i,
        label: trackLabel(t.lang, t.name, `Subtitle ${i + 1}`),
      })),
    setEmbeddedSub(id) {
      hls.subtitleTrack = id;
      hls.subtitleDisplay = id >= 0;
    },
    destroy() {
      hls.destroy();
      video.removeAttribute("src");
      video.load();
    },
  };
}

/** Reads the content's key id from the manifest, for a ClearKey link that doesn't name one. */
async function manifestKid(url: string): Promise<string | null> {
  try {
    const text = await (await fetch(url)).text();
    return /default_KID="([0-9a-fA-F-]{32,36})"/.exec(text)?.[1]?.replace(/-/g, "") ?? null;
  } catch {
    return null;
  }
}

async function attachDash(
  video: HTMLVideoElement,
  stream: Stream,
  startAt: number,
  cb: EngineCallbacks,
): Promise<Engine> {
  const dashjs = await import("dashjs");
  const player = dashjs.MediaPlayer().create();
  const key = stream.clearKey;
  if (key) {
    const kid = key.kid ?? (await manifestKid(stream.url));
    if (kid)
      player.setProtectionData({
        "org.w3.clearkey": {
          clearkeys: { [hexToB64Url(kid)]: hexToB64Url(key.key) },
        },
      });
  }
  player.updateSettings({
    streaming: { buffer: { bufferTimeAtTopQuality: 60, bufferToKeep: 30 } },
  });
  const events = dashjs.MediaPlayer.events;
  player.on(events.ERROR, (e: { error?: { message?: string } | string }) =>
    cb.onFatal(typeof e.error === "string" ? e.error : (e.error?.message ?? "DASH error")),
  );
  player.on(events.PLAYBACK_ERROR, () => cb.onFatal("DASH playback error"));
  for (const ev of [
    events.STREAM_INITIALIZED,
    events.TRACK_CHANGE_RENDERED,
    events.QUALITY_CHANGE_RENDERED,
  ])
    player.on(ev, () => cb.onTracks());
  player.initialize(video, stream.url, true, startAt > 0 ? startAt : undefined);
  player.enableText(false);
  let chosen = -1;
  const audio = () => player.getTracksFor("audio");
  const texts = () => player.getTracksFor("text");
  return {
    kind: "dash",
    audioTracks() {
      const current = player.getCurrentTrackFor("audio");
      return audio().map((t, i) => ({
        id: i,
        label: trackLabel(t.lang, t.labels?.[0]?.text, `Audio ${i + 1}`),
        selected: t === current || (!!current && t.id === current.id),
      }));
    },
    setAudio(id) {
      const t = audio()[id];
      if (t) player.setCurrentTrack(t);
    },
    levels: () =>
      player
        .getRepresentationsByType("video")
        .map((r, i) => ({ id: i, height: r.height, label: `${r.height}p` }))
        .sort((a, b) => b.height - a.height),
    level: () => chosen,
    setLevel(id) {
      chosen = id;
      player.updateSettings({
        streaming: { abr: { autoSwitchBitrate: { video: id < 0 } } },
      });
      if (id >= 0) player.setRepresentationForTypeByIndex("video", id, true);
      cb.onTracks();
    },
    embeddedSubs: () =>
      texts().map((t, i) => ({
        id: i,
        label: trackLabel(t.lang, t.labels?.[0]?.text, `Subtitle ${i + 1}`),
      })),
    setEmbeddedSub(id) {
      player.enableText(id >= 0);
      if (id >= 0) player.setTextTrack(id);
    },
    destroy() {
      player.destroy();
    },
  };
}
