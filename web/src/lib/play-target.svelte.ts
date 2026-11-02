import { api } from "./api";
import { cast } from "./cast.svelte";
import type { DeviceKind, Status } from "./types";

/** Where an episode plays: "browser" (here), or a cast device's id; "mpv" is the server's screen. */
export type PlayTarget = string;

const KEY = "play-target";

function load(): PlayTarget | null {
  try {
    return localStorage.getItem(KEY) || null;
  } catch {
    return null;
  }
}

/** Where this device plays, like Jellyfin's cast button: this browser until another device is picked, and it stays picked. */
class PlayTargets {
  /** The saved pick; null until one is made, which means this browser. */
  saved = $state<PlayTarget | null>(load());
  status = $state<Pick<Status, "mpvAvailable" | "loopback"> | null>(null);

  get mpvAvailable() {
    return !!this.status?.mpvAvailable;
  }

  /** The player the next play uses; falls back to the browser while the picked device is away, without forgetting it. */
  get effective(): PlayTarget {
    const t = this.saved ?? "browser";
    if (t === "browser") return t;
    if (t === "mpv") return this.mpvAvailable ? t : "browser";
    return cast.targets.some((d) => d.id === t) ? t : "browser";
  }

  /** Playing somewhere other than this browser. */
  get remote() {
    return this.effective !== "browser";
  }

  /** The kind of player `effective` is, for the cast button's icon. */
  get kind(): DeviceKind | "browser" {
    const t = this.effective;
    return t === "browser" ? t : (cast.device(t)?.kind ?? "browser");
  }

  /** The name of the player `effective` is. */
  get label(): string {
    const t = this.effective;
    return this.options.find((o) => o.id === t)?.label ?? "This browser";
  }

  /** The choices in the cast menu: here, mpv (greyed out when unavailable), then the connected devices. */
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

  select(t: PlayTarget) {
    this.saved = t;
    try {
      localStorage.setItem(KEY, t);
    } catch {
      // Applies until the page reloads.
    }
  }
}

export const playTarget = new PlayTargets();
