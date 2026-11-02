import { api } from "./api";
import type { Status } from "./types";

/** Where an episode plays: in this browser, or in mpv on the server's screen. Casting to a TV comes later. */
export type PlayTarget = "browser" | "mpv";

export const PLAY_TARGETS: { id: PlayTarget; label: string }[] = [
  { id: "browser", label: "This browser" },
  { id: "mpv", label: "Server mpv" },
];

const KEY = "play-target";

function load(): PlayTarget | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === "browser" || v === "mpv" ? v : null;
  } catch {
    return null;
  }
}

/** This device's default player, plus a one-off choice from the sources sheet. */
class PlayTargets {
  /** The saved default; null until chosen, when it follows the server (mpv on the desktop itself). */
  saved = $state<PlayTarget | null>(load());
  /** Chosen in the sources sheet for the episode on screen. */
  override = $state<PlayTarget | null>(null);
  status = $state<Pick<Status, "mpvAvailable" | "loopback"> | null>(null);

  get mpvAvailable() {
    return !!this.status?.mpvAvailable;
  }

  get default(): PlayTarget {
    return this.saved ?? (this.status?.loopback && this.mpvAvailable ? "mpv" : "browser");
  }

  /** The player the next play uses; falls back to the browser when the server can't run mpv. */
  get effective(): PlayTarget {
    const t = this.override ?? this.default;
    return t === "mpv" && !this.mpvAvailable ? "browser" : t;
  }

  async refresh() {
    try {
      this.status = await api.status();
    } catch {
      // Keeps the last known state; the browser player works either way.
    }
  }

  setDefault(t: PlayTarget) {
    this.saved = t;
    try {
      localStorage.setItem(KEY, t);
    } catch {
      // Applies until the page reloads.
    }
  }
}

export const playTarget = new PlayTargets();
