import { api } from "./api";
import { cast } from "./cast.svelte";
import type { PlayerState } from "./types";

const initial: PlayerState = {
  status: "idle",
  position: 0,
  duration: 0,
  paused: false,
  volume: 100,
  audioTracks: [],
  subTracks: [],
  hasNext: false,
  hasPrev: false,
  mpvRunning: false,
};

const isActive = (s: PlayerState | undefined) => !!s && (s.status !== "idle" || !!s.title);

/**
 * The remote: the player on the device this browser is casting to (the one it has claimed), shared by the now-playing
 * bar, the remote and the sources sheet. Live state comes over the cast socket. Other devices' players aren't shown
 * here, so what someone else casts never gets in the way of playing on this one.
 */
class Player {
  /** Shows the full-screen remote. */
  expanded = $state(false);

  /** The device the remote controls: the one the server lists as picked by this browser, or none. */
  get device(): string | null {
    return cast.others.find((d) => d.controller === cast.id)?.id ?? null;
  }

  get info() {
    return cast.device(this.device);
  }

  get state(): PlayerState {
    const d = this.device;
    return (d && cast.states[d]) || initial;
  }

  get connected() {
    return cast.connected;
  }

  get active() {
    return isActive(this.state);
  }

  start() {
    cast.start();
  }

  command(cmd: string, body: Record<string, unknown> = {}) {
    const device = this.device;
    if (!device) return Promise.reject(new Error("Not casting to a device"));
    return api.castCommand(device, cmd, body);
  }
}

export const player = new Player();
