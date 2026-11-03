import { api, ApiError, episodeLabel, streamLinks } from "./api";
import {
  attach,
  type AudioTrack,
  type EmbeddedSub,
  type Engine,
  type Level,
} from "./player/engine";
import { prefs } from "./player/prefs.svelte";
import {
  autoPick,
  currentSkip,
  orderSubtitles,
  PREFETCH_FRACTION,
  preferredLanguage,
  rankedLinks,
  SUCCESS_AFTER_SECONDS,
} from "./player/rules";
import { srtToVtt } from "./player/srt-to-vtt";
import { sources } from "./sources.svelte";
import type {
  Details,
  Episode,
  Link,
  ProgressRequest,
  Settings,
  SkipRange,
  Subtitle,
  SubtitleFormat,
} from "./types";

/** One title being played: its episodes (one dub for anime) and which one is current; movies have none. */
export interface Item {
  details: Details;
  episodes: Episode[];
  index: number;
  data: string;
}

function itemOf(details: Details, data: string): Item {
  const target = details.episodes.find((e) => e.data === data);
  if (!target || details.movieData === data) return { details, episodes: [], index: -1, data };
  const list = details.episodes.filter((e) => e.dub === target.dub);
  return { details, episodes: list, index: list.indexOf(target), data };
}

const itemAt = (item: Item, i: number): Item => ({
  ...item,
  index: i,
  data: item.episodes[i].data,
});
const episodeOf = (item: Item) => item.episodes[item.index] as Episode | undefined;

/** A side-loaded subtitle fetched for the browser: WebVTT as a blob URL, ASS as text for JASSUB. */
export interface LoadedSub {
  id: string;
  lang: string;
  format: SubtitleFormat;
  src?: string;
  text?: string;
}

const MIME_FORMATS: Record<string, SubtitleFormat> = {
  "text/x-ssa": "ass",
  "text/vtt": "vtt",
  "application/x-subrip": "srt",
  "application/ttml+xml": "ttml",
};

type Status = "resolving" | "loading" | "playing" | "error" | "ended";

/**
 * The player in this browser, a port of the phone app's `PlayerViewModel`: auto-picks the best link and falls back
 * to the next when one fails, adds subtitles, saves progress, skips openings and plays the next episode.
 */
class WebPlayer {
  open = $state(false);
  /** Shrunk to a floating window so the rest of the app can be used while it plays. */
  minimized = $state(false);
  /** A swipe-seek is under way: the video is held paused until the finger lifts. */
  scrubbing = $state(false);
  item = $state<Item | null>(null);
  status = $state<Status>("resolving");
  message = $state<string | null>(null);
  current = $state<Link | null>(null);
  failed = $state<string[]>([]);

  loadedSubs = $state<LoadedSub[]>([]);
  /** The side-loaded subtitle on screen, or null for an embedded one or none. */
  subtitle = $state<string | null>(null);
  embeddedSub = $state(-1);
  /** The embedded subtitle's cues, when the screen draws them (MKV text tracks). */
  embeddedTrack = $state.raw<TextTrack | null>(null);
  subtitlesOff = $state(false);

  audioTracks = $state<AudioTrack[]>([]);
  levels = $state<Level[]>([]);
  level = $state(-1);
  embeddedSubs = $state<EmbeddedSub[]>([]);

  position = $state(0);
  duration = $state(0);
  buffered = $state(0);
  paused = $state(true);
  buffering = $state(false);
  rate = $state(1);

  skips = $state<SkipRange[]>([]);
  /** The opening/ending the position is in, while the player offers to skip it. */
  skip = $state<SkipRange | null>(null);
  /** Seconds until the next episode starts, while the end card counts down. */
  upNext = $state<number | null>(null);
  /** Asks the screen to open the source picker (auto-play is off, or every link failed). */
  pickSource = $state(false);

  video: HTMLVideoElement | null = null;
  private engine: Engine | null = null;
  private jassub: {
    destroy(): Promise<void>;
    renderer: { setTrack(t: string): unknown };
  } | null = null;
  private settings: Settings | null = null;
  private tried = new Set<string>();
  private chosenSubs: string[] = [];
  private skipped = new Set<SkipRange>();
  private startAt: number | null = null;
  private playStartedAt: number | null = null;
  private recordedSuccess = false;
  /** Whether an MKV's own subtitle has been considered for showing, for the current link. */
  private embeddedPicked = false;
  private skipsRequested = false;
  private lastSaved = 0;
  private session = 0;
  private pickTimer: ReturnType<typeof setInterval> | undefined;
  private tickTimer: ReturnType<typeof setInterval> | undefined;
  private countdown: ReturnType<typeof setInterval> | undefined;
  private prefetch: (() => void) | null | undefined;
  private videoWaiters: ((v: HTMLVideoElement) => void)[] = [];
  private resumeAfterScrub = false;
  /** Called once a forced save (pause, close, end) has reached the server, so screens can refetch progress. */
  onProgressSaved: (() => void) | null = null;

  get profile() {
    const s = this.settings;
    return s?.source_profiles.find((p) => p.id === s.source_profile);
  }

  get episode() {
    return this.item ? episodeOf(this.item) : undefined;
  }

  get label() {
    const item = this.item;
    const ep = item && episodeOf(item);
    return item && ep ? episodeLabel(ep, item.index) : null;
  }

  get hasNext() {
    return !!this.item && this.item.index >= 0 && this.item.index + 1 < this.item.episodes.length;
  }

  get hasPrev() {
    return !!this.item && this.item.index > 0;
  }

  /** Whether this episode is the one playing here (for the sources sheet). */
  playing(provider: string, data: string) {
    return this.open && this.item?.details.provider === provider && this.item.data === data;
  }

  ranked() {
    return rankedLinks(sources.links, this.profile, new Set(this.failed), this.current?.id);
  }

  // ---- the <video> -------------------------------------------------------------------------------------------

  bind(video: HTMLVideoElement) {
    this.video = video;
    video.volume = prefs.value.volume;
    video.muted = prefs.value.muted;
    for (const w of this.videoWaiters.splice(0)) w(video);
  }

  unbind() {
    this.video = null;
  }

  private waitVideo(): Promise<HTMLVideoElement> {
    return this.video
      ? Promise.resolve(this.video)
      : new Promise((resolve) => this.videoWaiters.push(resolve));
  }

  /** Events from the `<video>` the screen forwards. */
  onVideoEvent(type: string) {
    const v = this.video;
    if (!v) return;
    switch (type) {
      case "playing":
        this.buffering = false;
        if (this.status === "loading") {
          this.status = "playing";
          this.message = null;
        }
        break;
      case "waiting":
        this.buffering = true;
        break;
      case "pause":
        // A swipe-seek holds the video still; that isn't the viewer pausing.
        if (this.scrubbing) break;
        this.paused = true;
        this.saveProgress(true);
        break;
      case "play":
        this.paused = false;
        break;
      case "ended":
        this.onEnded();
        break;
      case "ratechange":
        this.rate = v.playbackRate;
        break;
      case "volumechange":
        prefs.update({ volume: v.volume, muted: v.muted });
        break;
    }
  }

  // ---- starting --------------------------------------------------------------------------------------------

  /**
   * Opens the player on [data] of a title and starts it. [linkId] is a link known only by id (cast from another
   * device), played once it streams in.
   */
  async start(
    target: { provider: string; url: string; data: string; details?: Details },
    at?: number,
    link?: Link,
    subs: string[] = [],
    linkId?: string,
  ) {
    this.open = true;
    this.minimized = false;
    this.status = "resolving";
    this.message = "Loading…";
    try {
      const [details, settings] = await Promise.all([
        target.details ?? api.details(target.provider, target.url),
        api.settings(),
      ]);
      this.settings = settings;
      this.openItem(itemOf(details, target.data), at ?? null, {
        link,
        subs,
        linkId,
      });
    } catch (e) {
      this.status = "error";
      this.message = e instanceof Error ? e.message : String(e);
    }
  }

  private openItem(
    item: Item,
    at: number | null,
    opts: {
      link?: Link;
      linkId?: string;
      fresh?: boolean;
      subs?: string[];
    } = {},
  ) {
    this.saveProgress(true);
    this.reset();
    this.chosenSubs = [...(opts.subs ?? [])];
    const session = ++this.session;
    this.item = item;
    this.startAt = at;
    this.status = "resolving";
    this.message = "Finding links…";
    this.failed = [];
    this.current = null;
    this.subtitle = null;
    this.subtitlesOff = false;
    this.embeddedSub = -1;
    this.embeddedTrack = null;
    this.skips = [];
    this.skip = null;
    this.upNext = null;
    this.pickSource = false;

    const d = item.details;
    sources.load(
      {
        provider: d.provider,
        url: d.url,
        data: item.data,
        title: d.name,
        label: this.label,
      },
      opts.fresh,
    );
    if (at == null)
      api
        .resume(d.provider, item.data)
        .then((r) => {
          if (session === this.session && this.startAt == null) this.startAt = r.position ?? null;
        })
        .catch(() => {});
    this.tickTimer = setInterval(() => this.tick(), 500);
    this.updateMediaSession();
    if (opts.link) void this.play(opts.link);
    else if (opts.linkId) this.playWhenListed(opts.linkId);
    else if (this.settings?.autoplay_best ?? true) this.autoPick(false);
    else {
      this.message = "Choose a source";
      this.pickSource = true;
    }
  }

  /** Stops the current stream and timers, keeping the item. */
  private reset() {
    clearInterval(this.pickTimer);
    clearInterval(this.tickTimer);
    clearInterval(this.countdown);
    this.prefetch?.();
    this.prefetch = undefined;
    this.detach();
    this.tried.clear();
    this.skipped.clear();
    this.skipsRequested = false;
    for (const s of this.loadedSubs) if (s.src) URL.revokeObjectURL(s.src);
    this.loadedSubs = [];
    this.position = 0;
    this.duration = 0;
    this.buffered = 0;
  }

  private detach() {
    this.engine?.destroy();
    this.engine = null;
    void this.jassub?.destroy();
    this.jassub = null;
    this.audioTracks = [];
    this.levels = [];
    this.level = -1;
    this.embeddedSubs = [];
    this.embeddedSub = -1;
    this.embeddedTrack = null;
  }

  private autoPick(fallback: boolean) {
    clearInterval(this.pickTimer);
    const run = () => {
      const pick = autoPick(
        sources.links,
        this.profile,
        new Set(this.failed),
        this.tried,
        sources.done,
        fallback,
      );
      if (pick === null) return;
      clearInterval(this.pickTimer);
      if (pick !== "none") {
        void this.play(pick);
        return;
      }
      this.status = "error";
      this.pickSource = true;
      if (this.tried.size) this.message = "All links failed";
      else if (!sources.links.some((l) => l.playable))
        this.message = (!sources.links.length && sources.error) || "No playable links";
      else this.message = "No playable links match this source profile. Pick one yourself.";
    };
    this.pickTimer = setInterval(run, 250);
    run();
  }

  /** Plays the link with [id] once it's among the episode's links; if it never turns up (expired), picks as usual. */
  private playWhenListed(id: string) {
    clearInterval(this.pickTimer);
    const run = () => {
      const link = sources.links.find((l) => l.id === id);
      if (link) {
        clearInterval(this.pickTimer);
        void this.play(link);
      } else if (sources.done) this.autoPick(false);
    };
    this.pickTimer = setInterval(run, 250);
    run();
  }

  /** Plays [link], from the current position when switching sources mid-episode. */
  async play(link: Link, from?: number) {
    clearInterval(this.pickTimer);
    const item = this.item;
    if (!item) return;
    const session = this.session;
    const at = from ?? this.startAt ?? 0;
    this.tried.add(link.id);
    this.playStartedAt = null;
    this.recordedSuccess = false;
    this.embeddedPicked = false;
    this.current = link;
    this.status = "loading";
    this.message = `Loading ${link.source} ${link.qualityLabel}…`;
    this.pickSource = false;
    this.upNext = null;
    this.detach();
    try {
      const [stream, video] = await Promise.all([api.stream(link.id), this.waitVideo()]);
      if (session !== this.session || this.current !== link) return;
      this.engine = await attach(video, stream, at, {
        onFatal: (reason) => {
          if (this.current === link) this.onLinkFailed(reason);
        },
        onTracks: () => this.syncTracks(),
      });
      video.play().catch(() => {
        // Autoplay with sound can be blocked until the user interacts; they press play.
        this.paused = true;
      });
      void this.applyDefaultSubtitles(link);
    } catch (e) {
      if (session !== this.session) return;
      if (e instanceof ApiError && e.status === 410) {
        // The server forgot these links (they expire); resolve again from where we were.
        this.openItem(item, this.position > 5 ? this.position : at, {
          fresh: true,
        });
      } else this.onLinkFailed(e instanceof Error ? e.message : String(e));
    }
  }

  private onLinkFailed(reason: string) {
    const failed = this.current;
    if (!failed || this.failed.includes(failed.id)) return;
    api.reportLink(failed.id, false).catch(() => {});
    if (this.position > 5) this.startAt = this.position;
    this.failed = [...this.failed, failed.id];
    this.detach();
    const next = this.ranked().find((l) => !this.tried.has(l.id));
    console.info(`Link ${failed.source} failed (${reason}); next: ${next?.source}`);
    this.status = "resolving";
    this.message = next
      ? `Link failed, trying ${next.source} ${next.qualityLabel}…`
      : "Link failed, waiting for more links…";
    this.autoPick(true);
  }

  private syncTracks() {
    const e = this.engine;
    if (!e) return;
    this.audioTracks = e.audioTracks();
    this.levels = e.levels();
    this.level = e.level();
    this.embeddedSubs = e.embeddedSubs();
    if (e.kind === "native" && this.embeddedSubs.length && !this.embeddedPicked) {
      this.embeddedPicked = true;
      this.pickEmbedded();
    }
  }

  /**
   * An MKV's own subtitles are timed to that very file, so a preferred-language one is shown unless the viewer already
   * chose something: a full track, or for a dub only a forced (signs and songs) one.
   */
  private pickEmbedded() {
    if (
      this.subtitle != null ||
      this.subtitlesOff ||
      this.embeddedSub >= 0 ||
      this.chosenSubs.length
    )
      return;
    const subs = this.embeddedSubs.filter((s) => s.lang && preferredLanguage(s.lang));
    const pick =
      this.episode?.dub === "Dubbed"
        ? subs.find((s) => s.forced)
        : (subs.find((s) => !s.forced && s.default) ?? subs.find((s) => !s.forced));
    if (pick) this.selectEmbedded(pick.id);
  }

  setAudio(id: number) {
    this.engine?.setAudio(id);
    this.syncTracks();
  }

  setLevel(id: number) {
    this.engine?.setLevel(id);
  }

  // ---- subtitles -------------------------------------------------------------------------------------------

  /** Chosen ones, plus up to three in the preferred language; the first of those is shown unless it's a dub. */
  private async applyDefaultSubtitles(link: Link) {
    const preferred = sources.subtitles.filter(
      (s) => !this.chosenSubs.includes(s.id) && preferredLanguage(s.lang),
    );
    const auto = orderSubtitles(preferred, link, sources.done).slice(0, 3);
    const chosen = sources.subtitles.filter((s) => this.chosenSubs.includes(s.id));
    const select =
      this.subtitle == null && !this.subtitlesOff && this.embeddedSub < 0
        ? (chosen[0] ?? (this.episode?.dub !== "Dubbed" ? auto[0] : undefined))
        : undefined;
    await Promise.all([...chosen, ...auto].map((s) => this.loadSub(s).catch(() => null)));
    // An MKV's own subtitle picked meanwhile stays, unless the viewer chose this one.
    if (select && this.current === link && (chosen.includes(select) || this.embeddedSub < 0))
      await this.selectSubtitle(select, false);
    else if (this.subtitle) await this.showSubtitle(this.subtitle);
  }

  /** Subtitles arriving after the link started: add the first preferred one if none is on yet. */
  onSubtitleArrived() {
    const link = this.current;
    if (!link || this.status !== "playing" || this.subtitle || this.subtitlesOff) return;
    if (this.embeddedSub >= 0 || this.episode?.dub === "Dubbed") return;
    const pick = orderSubtitles(
      sources.subtitles.filter((s) => preferredLanguage(s.lang)),
      link,
      sources.done,
    )[0];
    if (pick) void this.selectSubtitle(pick, false);
  }

  /** Downloads a subtitle once; the server sniffs the format and serves it with the right type. */
  private async loadSub(sub: Subtitle): Promise<LoadedSub> {
    const have = this.loadedSubs.find((s) => s.id === sub.id);
    if (have) return have;
    const res = await fetch(api.subtitleUrl(sub.id));
    if (!res.ok) throw new Error(`Subtitle ${sub.lang}: HTTP ${res.status}`);
    const type = (res.headers.get("content-type") ?? "").split(";")[0].trim();
    const format = MIME_FORMATS[type] ?? sub.format ?? "srt";
    const text = await res.text();
    let loaded: LoadedSub;
    if (format === "ass") loaded = { id: sub.id, lang: sub.lang, format, text };
    else if (format === "ttml") throw new Error("TTML subtitles aren't supported in the browser");
    else {
      const vtt = format === "srt" ? srtToVtt(text) : text;
      loaded = {
        id: sub.id,
        lang: sub.lang,
        format,
        src: URL.createObjectURL(new Blob([vtt], { type: "text/vtt" })),
      };
    }
    if (!this.loadedSubs.some((s) => s.id === sub.id))
      this.loadedSubs = [...this.loadedSubs, loaded];
    return loaded;
  }

  /** Shows a side-loaded subtitle, fetching it first if needed. */
  async selectSubtitle(sub: Subtitle, chosen = true) {
    if (chosen && !this.chosenSubs.includes(sub.id)) this.chosenSubs.push(sub.id);
    try {
      await this.loadSub(sub);
    } catch (e) {
      this.message = e instanceof Error ? e.message : String(e);
      return;
    }
    this.subtitle = sub.id;
    this.subtitlesOff = false;
    if (this.embeddedSub >= 0) {
      this.embeddedSub = -1;
      this.embeddedTrack = null;
      this.engine?.setEmbeddedSub(-1);
    }
    await this.showSubtitle(sub.id);
  }

  /** WebVTT tracks are drawn by the screen; ASS goes to libass (JASSUB) over the video. */
  private async showSubtitle(id: string) {
    const loaded = this.loadedSubs.find((s) => s.id === id);
    if (loaded?.format !== "ass" || !loaded.text) {
      void this.jassub?.destroy();
      this.jassub = null;
      return;
    }
    const video = this.video;
    if (!video) return;
    if (this.jassub) {
      this.jassub.renderer.setTrack(loaded.text);
      return;
    }
    const { default: JASSUB } = await import("jassub");
    if (this.subtitle !== id) return;
    this.jassub = new JASSUB({
      video,
      subContent: loaded.text,
      queryFonts: "local",
    });
  }

  selectEmbedded(id: number) {
    this.subtitle = null;
    this.subtitlesOff = false;
    void this.jassub?.destroy();
    this.jassub = null;
    this.embeddedSub = id;
    this.engine?.setEmbeddedSub(id);
    this.embeddedTrack = this.engine?.embeddedTextTrack?.() ?? null;
  }

  subtitlesOffNow() {
    this.subtitle = null;
    this.subtitlesOff = true;
    this.embeddedSub = -1;
    this.embeddedTrack = null;
    this.engine?.setEmbeddedSub(-1);
    void this.jassub?.destroy();
    this.jassub = null;
  }

  /** C on the keyboard: off, then each loaded subtitle in turn. */
  cycleSubtitle() {
    const subs = this.loadedSubs;
    const i = subs.findIndex((s) => s.id === this.subtitle);
    const next = subs[i + 1];
    if (next) void this.selectSubtitle({ id: next.id, lang: next.lang, url: "" });
    else this.subtitlesOffNow();
  }

  // ---- progress, skips, the end ----------------------------------------------------------------------------

  private tick() {
    const v = this.video;
    const item = this.item;
    if (!v || !item || !this.engine) return;
    this.position = v.currentTime;
    this.duration = isFinite(v.duration) ? v.duration : 0;
    const b = v.buffered;
    for (let i = 0; i < b.length; i++)
      if (b.start(i) <= v.currentTime + 0.5 && b.end(i) >= v.currentTime) this.buffered = b.end(i);
    if (this.status !== "playing") return;
    const pos = this.position;
    const dur = this.duration;
    if (!v.paused) {
      this.playStartedAt ??= Date.now();
      if (
        !this.recordedSuccess &&
        Date.now() - this.playStartedAt >= SUCCESS_AFTER_SECONDS * 1000
      ) {
        this.recordedSuccess = true;
        if (this.current) api.reportLink(this.current.id, true).catch(() => {});
      }
      this.saveProgress();
    }
    const mode = this.settings?.skip_segments ?? "prompt";
    if (dur > 0 && !this.skipsRequested) {
      this.skipsRequested = true;
      this.loadSkips(item, dur);
    }
    const range = currentSkip(this.skips, pos);
    const offer = range && mode !== "off" && !this.skipped.has(range) ? range : null;
    if (mode === "auto" && offer) this.skipNow(offer);
    else if (offer !== this.skip) this.skip = offer;
    // Warm the server's link cache for the next episode near the end.
    if (dur > 0 && pos > dur * PREFETCH_FRACTION && this.hasNext && this.prefetch === undefined) {
      const next = itemAt(item, item.index + 1);
      this.prefetch = streamLinks(item.details.provider, next.data, {
        link: () => {},
        subtitle: () => {},
        error: () => {},
        done: () => (this.prefetch = null),
      });
    }
    if ("mediaSession" in navigator && dur > 0)
      try {
        navigator.mediaSession.setPositionState({
          duration: dur,
          position: Math.min(pos, dur),
          playbackRate: v.playbackRate,
        });
      } catch {
        // Some browsers reject a position state while the duration settles.
      }
  }

  private loadSkips(item: Item, duration: number) {
    const d = item.details;
    const episode = episodeOf(item)?.episode;
    if (episode == null || d.kind !== "anime" || (d.malId == null && d.aniListId == null)) return;
    if (this.settings?.skip_segments === "off") return;
    api
      .skips({ episode, duration, malId: d.malId, aniListId: d.aniListId })
      .then((ranges) => {
        if (this.item === item) this.skips = ranges;
      })
      .catch(() => {});
  }

  skipNow(range = this.skip) {
    if (!range || !this.video) return;
    this.skipped.add(range);
    this.video.currentTime = range.end;
    this.skip = null;
  }

  private progressRequest(): ProgressRequest | null {
    const item = this.item;
    if (!item || !this.current || this.duration <= 0 || this.position <= 0) return null;
    return {
      provider: item.details.provider,
      url: item.details.url,
      data: item.data,
      position: this.position,
      duration: this.duration,
      label: this.label,
    };
  }

  private saveProgress(force = false) {
    if (this.video && this.engine) this.position = this.video.currentTime;
    const req = this.progressRequest();
    if (!req) return;
    const now = Date.now();
    if (!force && now - this.lastSaved < 5000) return;
    this.lastSaved = now;
    api.progress(req).then(
      () => force && this.onProgressSaved?.(),
      () => {},
    );
  }

  /** The page is closing: a request that outlives it. */
  saveOnExit() {
    const req = this.progressRequest();
    if (req) api.progressBeacon(req);
  }

  private onEnded() {
    const item = this.item;
    if (!item) return;
    const dur = this.duration;
    api
      .progress({
        provider: item.details.provider,
        url: item.details.url,
        data: item.data,
        watched: true,
        position: dur,
        duration: dur,
        label: this.label,
      })
      .then(
        () => this.onProgressSaved?.(),
        () => {},
      );
    this.status = "ended";
    if (this.settings?.autoplay_next && this.hasNext) {
      clearInterval(this.countdown);
      this.upNext = 5;
      this.countdown = setInterval(() => {
        if (this.upNext == null) return clearInterval(this.countdown);
        if (--this.upNext <= 0) this.next();
      }, 1000);
    } else this.message = "Finished";
  }

  cancelUpNext() {
    clearInterval(this.countdown);
    this.upNext = null;
  }

  next() {
    const item = this.item;
    if (item && this.hasNext) this.openItem(itemAt(item, item.index + 1), null);
  }

  prev() {
    const item = this.item;
    if (item && this.hasPrev) this.openItem(itemAt(item, item.index - 1), null);
  }

  episodeAt(index: number) {
    const item = this.item;
    if (item && index >= 0 && index < item.episodes.length)
      this.openItem(itemAt(item, index), null);
  }

  retry() {
    const item = this.item;
    if (item)
      this.openItem(item, this.position > 5 ? this.position : this.startAt, {
        fresh: true,
      });
  }

  /** A link picked in the sources sheet: keeps the position when switching mid-episode. */
  pick(link: Link) {
    void this.play(link, this.position > 0 ? this.position : undefined);
  }

  /** Subtitles chosen in the sources sheet, added when the next link starts. */
  addChosenSubs(ids: string[]) {
    for (const id of ids) if (!this.chosenSubs.includes(id)) this.chosenSubs.push(id);
  }

  close() {
    this.saveProgress(true);
    this.reset();
    this.session++;
    this.open = false;
    this.minimized = false;
    this.scrubbing = false;
    this.item = null;
    this.current = null;
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    if ("mediaSession" in navigator) navigator.mediaSession.metadata = null;
  }

  // ---- media keys and the OS media controls ------------------------------------------------------------------

  private updateMediaSession() {
    if (!("mediaSession" in navigator) || !this.item) return;
    const d = this.item.details;
    const art = this.episode?.poster ?? d.poster;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: this.label ?? d.name,
      artist: this.label ? d.name : undefined,
      artwork: art ? [{ src: art }] : [],
    });
    const handlers: [MediaSessionAction, MediaSessionActionHandler | null][] = [
      ["play", () => void this.video?.play()],
      ["pause", () => this.video?.pause()],
      ["seekbackward", (e) => this.seekBy(-(e.seekOffset ?? prefs.value.seekStep))],
      ["seekforward", (e) => this.seekBy(e.seekOffset ?? prefs.value.seekStep)],
      ["seekto", (e) => e.seekTime != null && this.seekTo(e.seekTime)],
      ["previoustrack", this.hasPrev ? () => this.prev() : null],
      ["nexttrack", this.hasNext ? () => this.next() : null],
    ];
    for (const [action, fn] of handlers)
      try {
        navigator.mediaSession.setActionHandler(action, fn);
      } catch {
        // Unsupported action in this browser.
      }
  }

  // ---- controls --------------------------------------------------------------------------------------------

  toggle() {
    const v = this.video;
    if (!v) return;
    if (this.status === "ended") {
      this.cancelUpNext();
      v.currentTime = 0;
      this.status = "playing";
    }
    if (v.paused) void v.play();
    else v.pause();
  }

  seekTo(seconds: number) {
    const v = this.video;
    if (!v) return;
    v.currentTime = Math.max(0, Math.min(seconds, this.duration || seconds));
    this.position = v.currentTime;
  }

  seekBy(seconds: number) {
    if (this.video) this.seekTo(this.video.currentTime + seconds);
  }

  /** A swipe-seek starts: hold the video still so the sound doesn't run on under the finger. */
  beginScrub() {
    const v = this.video;
    if (!v || this.scrubbing) return;
    this.scrubbing = true;
    this.resumeAfterScrub = !v.paused;
    v.pause();
  }

  /** The finger lifted: carry on playing if it was playing before. */
  endScrub() {
    if (!this.scrubbing) return;
    this.scrubbing = false;
    if (this.resumeAfterScrub) void this.video?.play();
    this.resumeAfterScrub = false;
  }

  setRate(rate: number) {
    if (this.video) this.video.playbackRate = rate;
  }
}

export const webPlayer = new WebPlayer();
