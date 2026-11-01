import { api, playerSocket } from "./api";
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

/** Live mpv state from the server's WebSocket, shared by the now-playing bar, the remote and the sources sheet. */
class Player {
  state = $state<PlayerState>(initial);
  connected = $state(false);
  /** Shows the full-screen remote. */
  expanded = $state(false);
  private stop: (() => void) | null = null;

  get active() {
    return this.state.status !== "idle" || !!this.state.title;
  }

  start() {
    this.stop ??= playerSocket(
      (s) => (this.state = s),
      (open) => (this.connected = open),
    );
  }

  command(cmd: string, body: Record<string, unknown> = {}) {
    return api.player(cmd, body);
  }
}

export const player = new Player();
