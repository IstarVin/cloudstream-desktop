import { api, streamLinks } from "./api";
import { cast } from "./cast.svelte";
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

  /** Starts playback of an episode on this device's player (or the device it casts to) and shows its sources. */
  async play(t: Target, startAt?: number) {
    playTarget.override = null;
    await playTarget.refresh();
    const target = playTarget.effective;
    if (target === "browser") {
      void webPlayer.start(t, startAt);
      return;
    }
    this.show(t);
    try {
      player.follow(target);
      await api.castPlay(
        target,
        { provider: t.provider, url: t.url, data: t.data, startAt },
        cast.id,
      );
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

  /**
   * A link picked in the sheet, played on the chosen target. [currentlyPlaying]: the episode is the one on the
   * remote's device, so moving it elsewhere stops it there and carries on from the same point.
   */
  async pick(link: Link, currentlyPlaying: boolean) {
    const t = this.target;
    if (!t) return;
    const target = playTarget.effective;
    const remoteAt = player.state.position > 5 ? player.state.position : undefined;
    if (target === "browser") {
      this.hide();
      if (currentlyPlaying) {
        player.command("stop").catch(() => {});
        await webPlayer.start(t, remoteAt, link, [...this.chosenSubs]);
      } else if (webPlayer.playing(t.provider, t.data)) {
        webPlayer.addChosenSubs(this.chosenSubs);
        webPlayer.pick(link);
      } else await webPlayer.start(t, undefined, link, [...this.chosenSubs]);
      return;
    }
    try {
      if (currentlyPlaying && player.device === target) {
        await player.command("source", { linkId: link.id });
        return;
      }
      let startAt: number | undefined;
      if (webPlayer.playing(t.provider, t.data)) {
        startAt = webPlayer.position > 5 ? webPlayer.position : undefined;
        webPlayer.close();
      } else if (currentlyPlaying) {
        startAt = remoteAt;
        await player.command("stop").catch(() => {});
      }
      player.follow(target);
      await api.castPlay(
        target,
        {
          provider: t.provider,
          url: t.url,
          data: t.data,
          linkId: link.id,
          subtitleIds: this.chosenSubs,
          startAt,
        },
        cast.id,
      );
    } catch (e) {
      toasts.error(e);
    }
  }

  hide() {
    this.open = false;
  }
}

export const sources = new Sources();
