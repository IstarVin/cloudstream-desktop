import { api, streamLinks } from "./api";
import { toasts } from "./toast.svelte";
import type { Link, Subtitle } from "./types";

interface Target {
  provider: string;
  url: string;
  data: string;
  title: string;
  label: string | null;
}

/**
 * The sources sheet: opens on Play, streams the episode's links as they arrive and lets you switch. Playback itself
 * (including auto-picking the best link) is decided by the server.
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

  /** Starts playback of an episode (the server auto-picks unless that's off) and shows its sources. */
  async play(t: Target, startAt?: number) {
    this.show(t);
    try {
      await api.play({ provider: t.provider, url: t.url, data: t.data, startAt });
    } catch (e) {
      toasts.error(e);
    }
  }

  /** Opens the sheet for an episode without starting playback (e.g. from the now-playing bar). */
  show(t: Target) {
    const same = this.target?.provider === t.provider && this.target?.data === t.data;
    this.target = t;
    this.open = true;
    if (same && (this.links.length || !this.done)) return;
    this.close?.();
    this.links = [];
    this.subtitles = [];
    this.chosenSubs = [];
    this.done = false;
    this.error = null;
    this.close = streamLinks(t.provider, t.data, {
      link: (l) => this.links.push(l),
      subtitle: (s) => this.subtitles.push(s),
      error: (m) => (this.error = m),
      done: () => (this.done = true),
    });
  }

  async pick(link: Link, currentlyPlaying: boolean) {
    const t = this.target;
    if (!t) return;
    try {
      if (currentlyPlaying) await api.player("source", { linkId: link.id });
      else
        await api.play({
          provider: t.provider,
          url: t.url,
          data: t.data,
          linkId: link.id,
          subtitleIds: this.chosenSubs,
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
