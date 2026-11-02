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
 * The remote: the player on another device (the server's mpv, a TV, another browser), shared by the now-playing bar,
 * the remote and the sources sheet. Live state comes over the cast socket.
 */
class Player {
  /** The device last cast to or picked in the remote; while it's gone, the bar follows whatever else is playing. */
  followed = $state<string | null>(null);
  /** Shows the full-screen remote. */
  expanded = $state(false);

  /** Other devices with something playing (or just finished), mpv first. */
  get playing() {
    return cast.others.filter((d) => isActive(cast.states[d.id]));
  }

  /** The device the remote controls. */
  get device(): string | null {
    const f = this.followed;
    if (f && cast.device(f) && (isActive(cast.states[f]) || !this.playing.length)) return f;
    return this.playing[0]?.id ?? (f && cast.device(f) ? f : null);
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

  follow(device: string) {
    this.followed = device;
  }

  command(cmd: string, body: Record<string, unknown> = {}) {
    return api.castCommand(this.device ?? "mpv", cmd, body);
  }
}

export const player = new Player();
