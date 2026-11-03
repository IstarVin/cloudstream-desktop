import type { EmbeddedCue, EmbeddedSubs, EmbeddedTrack } from "../types";

/**
 * Subtitles muxed into a Matroska file the browser plays itself. The browser never exposes those, so the server reads
 * them from the bytes its proxy passes to the `<video>` and this long-polls for them: tracks once the file's start is
 * in, cues as the video buffers. Text tracks become TextTracks; ASS goes to libass (JASSUB) block by block, the way
 * Matroska stores it.
 */
export interface MkvSubs {
  tracks(): EmbeddedTrack[];
  /** Shows the track at [index] of [tracks], or none with -1. */
  select(index: number): void;
  /** The shown text track, hidden from the browser's own renderer so the player can draw its cues; null for ASS or none. */
  textTrack(): TextTrack | null;
  destroy(): void;
}

type Jassub = InstanceType<typeof import("jassub").default>;

/** What libass needs to take Matroska's event lines, for a header that leaves it out. */
const ASS_EVENTS =
  "\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";

/** A Matroska text block as WebVTT cue text: ASS-style override tags dropped, `\N` as line breaks. */
export function cueText(text: string): string {
  return text
    .replace(/\{\\[^}]*\}/g, "")
    .replace(/\\N/gi, "\n")
    .replace(/\r\n?/g, "\n")
    .trim();
}

export function watchMkvSubs(
  video: HTMLVideoElement,
  stream: string,
  onTracks: () => void,
  label: (t: EmbeddedTrack, i: number) => string,
): MkvSubs {
  const ctrl = new AbortController();
  let tracks: EmbeddedTrack[] = [];
  const cues: EmbeddedCue[] = [];
  /** One TextTrack per text track shown at least once, by track number; added to as cues arrive. */
  const textTracks = new Map<number, TextTrack>();
  let selected = -1;
  let jassub: Jassub | null = null;
  let jassubReady = false;

  const addCue = (tt: TextTrack, c: EmbeddedCue) => {
    const text = cueText(c.text);
    if (!text) return;
    try {
      tt.addCue(new VTTCue(c.start / 1000, (c.start + c.duration) / 1000, text));
    } catch {
      // A cue the browser rejects (bad times) is skipped.
    }
  };

  const deliver = (c: EmbeddedCue) => {
    const tt = textTracks.get(c.track);
    if (tt) addCue(tt, c);
    const t = tracks[selected];
    if (t?.ass && t.number === c.track && jassub && jassubReady)
      void jassub.renderer.processChunk(c.text, c.start, c.duration);
  };

  const dropJassub = () => {
    void jassub?.destroy();
    jassub = null;
    jassubReady = false;
  };

  async function showAss(t: EmbeddedTrack) {
    const { default: JASSUB } = await import("jassub");
    if (tracks[selected] !== t || ctrl.signal.aborted) return;
    const header = t.header?.includes("[Events]")
      ? t.header
      : (t.header ?? "[Script Info]\nScriptType: v4.00+\n") + ASS_EVENTS;
    const j = new JASSUB({ video, subContent: header, queryFonts: "local" });
    jassub = j;
    await j.ready;
    if (jassub !== j) return;
    for (const c of cues)
      if (c.track === t.number) void j.renderer.processChunk(c.text, c.start, c.duration);
    jassubReady = true;
  }

  async function poll() {
    let since = 0;
    let version = 0;
    let failures = 0;
    while (!ctrl.signal.aborted) {
      try {
        const q = new URLSearchParams({
          stream,
          since: String(since),
          v: String(version),
          tracks: String(tracks.length),
        });
        const res = await fetch(`/api/embedded-subs?${q}`, {
          signal: ctrl.signal,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const r = (await res.json()) as EmbeddedSubs;
        if (r.matroska === false) return;
        if (r.tracks) {
          tracks = r.tracks;
          onTracks();
        }
        for (const c of r.cues) {
          cues.push(c);
          deliver(c);
        }
        since = r.next;
        version = r.version;
        failures = 0;
      } catch {
        if (ctrl.signal.aborted) return;
        await new Promise((r) => setTimeout(r, Math.min(30_000, 2000 * ++failures)));
      }
    }
  }
  void poll();

  return {
    tracks: () => tracks,
    select(index) {
      selected = index;
      for (const tt of textTracks.values()) tt.mode = "disabled";
      dropJassub();
      const t = tracks[index];
      if (!t) return;
      if (t.ass) {
        void showAss(t);
        return;
      }
      let tt = textTracks.get(t.number);
      if (!tt) {
        tt = video.addTextTrack("subtitles", label(t, index), t.lang ?? "");
        for (const c of cues) if (c.track === t.number) addCue(tt, c);
        textTracks.set(t.number, tt);
      }
      // Hidden, not showing: the player draws the cues itself (size, position, box), the browser only times them.
      tt.mode = "hidden";
    },
    textTrack() {
      const t = tracks[selected];
      return t && !t.ass ? (textTracks.get(t.number) ?? null) : null;
    },
    destroy() {
      ctrl.abort();
      dropJassub();
      // Tracks from addTextTrack outlive the source; empty and hide them.
      for (const tt of textTracks.values()) {
        tt.mode = "disabled";
        for (const c of [...(tt.cues ?? [])]) tt.removeCue(c);
      }
      textTracks.clear();
    },
  };
}
