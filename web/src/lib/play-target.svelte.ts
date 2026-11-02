import { api } from "./api";
import { cast } from "./cast.svelte";
import type { DeviceKind, Status } from "./types";

/** Where an episode plays: "browser" (here), or a cast device's id; "mpv" is the server's screen. */
export type PlayTarget = string;

/** The choices for this device's default player. Other devices come and go, so they're one-off picks. */
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

  /** The player the next play uses; falls back to the browser when the chosen one isn't there. */
  get effective(): PlayTarget {
    const t = this.override ?? this.default;
    if (t === "browser") return t;
    if (t === "mpv") return this.mpvAvailable ? t : "browser";
    return cast.targets.some((d) => d.id === t) ? t : "browser";
  }

  /** The choices in the sources sheet: here, mpv (greyed out when unavailable), then the connected devices. */
  get options(): {
    id: PlayTarget;
    label: string;
    kind: DeviceKind | "browser";
    disabled: boolean;
  }[] {
    const mpv = cast.device("mpv");
    return [
      { id: "browser", label: "This browser", kind: "browser", disabled: false },
      {
        id: "mpv",
        label: mpv?.name ?? "Server mpv",
        kind: "mpv",
        disabled: !this.mpvAvailable,
      },
      ...cast.targets
        .filter((d) => d.id !== "mpv")
        .map((d) => ({ id: d.id, label: d.name, kind: d.kind, disabled: false })),
    ];
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
