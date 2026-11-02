import { api, streamLinks } from "./api";
import { playTarget } from "./play-target.svelte";
import { player } from "./player.svelte";
import { toasts } from "./toast.svelte";
import type { Details, Link, Subtitle } from "./types";
import { webPlayer } from "./web-player.svelte";

export interface Target {
  provider: string;
  url: string;
  data: string;
  title: string;
  label: string | null;
  /** The title's details when the caller has them, so the browser player needn't load them again. */
  details?: Details;
}

/**
 * The sources sheet: opens on Play, streams the episode's links as they arrive and lets you switch. With mpv the
 * server plays and auto-picks; in the browser the web player does, from the links streamed here.
 */
class Sources {
  open = $state(false);
  target = $state<Target | null>(null);
  links = $state<Link[]>([]);
  subtitles = $state<Subtitle[]>([]);
  done = $state(false);
  error = $state<string | null>(null);
  chosenSubs = $state<string[]>([]);
  private close: (() => void) | null = null;

  /** Starts playback of an episode on this device's player and shows its sources. */
  async play(t: Target, startAt?: number) {
    playTarget.override = null;
    await playTarget.refresh();
    if (playTarget.effective === "browser") {
      void webPlayer.start(t, startAt);
      return;
    }
    this.show(t);
    try {
      await api.play({ provider: t.provider, url: t.url, data: t.data, startAt });
    } catch (e) {
      toasts.error(e);
    }
  }

  /** Opens the sheet for an episode without starting playback (e.g. from the now-playing bar). */
  show(t: Target) {
    this.load(t);
    this.open = true;
  }

  /** Streams an episode's links and subtitles, unless they're already here; [fresh] resolves them again. */
  load(t: Target, fresh = false) {
    const same = this.target?.provider === t.provider && this.target?.data === t.data;
    this.target = t;
    if (same && !fresh && (this.links.length || !this.done)) return;
    this.close?.();
    this.links = [];
    this.subtitles = [];
    this.chosenSubs = [];
    this.done = false;
    this.error = null;
    this.close = streamLinks(t.provider, t.data, {
      link: (l) => this.links.push(l),
      subtitle: (s) => {
        this.subtitles.push(s);
        webPlayer.onSubtitleArrived();
      },
      error: (m) => (this.error = m),
      done: () => {
        this.done = true;
        // Subtitles not labelled for the link's source only count once every link is in.
        webPlayer.onSubtitleArrived();
      },
    });
  }

  /** A link picked in the sheet, played on the chosen target. */
  async pick(link: Link, currentlyPlaying: boolean) {
    const t = this.target;
    if (!t) return;
    if (playTarget.effective === "browser") {
      this.hide();
      // Moving over from mpv: stop it there and carry on from the same point.
      if (currentlyPlaying) {
        const at = player.state.position;
        api.player("stop").catch(() => {});
        await webPlayer.start(t, at > 5 ? at : undefined, link, [...this.chosenSubs]);
      } else if (webPlayer.playing(t.provider, t.data)) {
        webPlayer.addChosenSubs(this.chosenSubs);
        webPlayer.pick(link);
      } else await webPlayer.start(t, undefined, link, [...this.chosenSubs]);
      return;
    }
    try {
      let startAt: number | undefined;
      if (webPlayer.playing(t.provider, t.data)) {
        startAt = webPlayer.position > 5 ? webPlayer.position : undefined;
        webPlayer.close();
      }
      if (currentlyPlaying) await api.player("source", { linkId: link.id });
      else
        await api.play({
          provider: t.provider,
          url: t.url,
          data: t.data,
          linkId: link.id,
          subtitleIds: this.chosenSubs,
          startAt,
        });
    } catch (e) {
      toasts.error(e);
    }
  }

  hide() {
    this.open = false;
  }
}

export const sources = new Sources();
