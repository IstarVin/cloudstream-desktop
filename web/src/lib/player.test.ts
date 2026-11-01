import { describe, expect, it, vi } from "vite-plus/test";
import type { PlayerState } from "./types";

const socket = vi.hoisted(() => ({
  onState: null as ((s: PlayerState) => void) | null,
  onOpen: null as ((open: boolean) => void) | null,
  opened: 0,
}));

vi.mock("./api", () => ({
  api: { player: vi.fn(async () => ({ ok: true })) },
  playerSocket: (onState: (s: PlayerState) => void, onOpen: (open: boolean) => void) => {
    socket.opened++;
    socket.onState = onState;
    socket.onOpen = onOpen;
    return () => {};
  },
}));

const { player } = await import("./player.svelte");

const state = (s: Partial<PlayerState>): PlayerState => ({ ...player.state, ...s });

describe("player store", () => {
  it("opens one socket however often it is started", () => {
    player.start();
    player.start();
    expect(socket.opened).toBe(1);
  });

  it("follows the server's state and connection", () => {
    player.start();
    expect(player.active).toBe(false);

    socket.onOpen?.(true);
    expect(player.connected).toBe(true);

    socket.onState?.(state({ status: "resolving", title: "Dune", message: "Resolving links…" }));
    expect(player.active).toBe(true);
    expect(player.state.message).toBe("Resolving links…");

    socket.onState?.(state({ status: "idle", title: null }));
    expect(player.active).toBe(false);

    socket.onOpen?.(false);
    expect(player.connected).toBe(false);
  });

  it("keeps the bar up after playback finishes while the title is known", () => {
    socket.onState?.(state({ status: "idle", title: "Dune", message: "Finished" }));
    expect(player.active).toBe(true);
  });
});
