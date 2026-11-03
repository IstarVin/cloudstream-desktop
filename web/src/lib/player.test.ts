import { describe, expect, it, vi } from "vite-plus/test";
import type { DeviceInfo, PlayerState } from "./types";

vi.mock("./api", () => ({
  api: { castCommand: vi.fn(async () => ({ ok: true })) },
  castSocket: () => ({ send() {}, stop() {} }),
}));

const { api } = await import("./api");
const { cast } = await import("./cast.svelte");
const { player } = await import("./player.svelte");

const idle: PlayerState = {
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
const state = (s: Partial<PlayerState>): PlayerState => ({ ...idle, ...s });
const dev = (id: string, kind: DeviceInfo["kind"] = "tv", controller?: string): DeviceInfo => ({
  id,
  name: id.toUpperCase(),
  kind,
  receiver: true,
  controller,
});
const me = () => dev(cast.id, "web");

describe("player store (the remote)", () => {
  it("ignores devices playing for someone else, or for nobody", () => {
    cast.onFrame({
      type: "devices",
      devices: [dev("mpv", "mpv", "other"), dev("tv"), me()],
    });
    cast.onFrame({
      type: "state",
      device: "mpv",
      state: state({ status: "playing", title: "Up" }),
    });
    cast.onFrame({
      type: "state",
      device: "tv",
      state: state({ status: "playing", title: "Dune" }),
    });
    expect(player.device).toBe(null);
    expect(player.active).toBe(false);
    expect(player.state.title).toBeUndefined();
  });

  it("shows the device this browser has claimed, and sends commands there", async () => {
    cast.onFrame({
      type: "devices",
      devices: [dev("mpv", "mpv", "other"), dev("tv", "tv", cast.id), me()],
    });
    expect(player.device).toBe("tv");
    expect(player.active).toBe(true);
    expect(player.state.title).toBe("Dune");
    await player.command("seek", { seconds: 10, relative: true });
    expect(api.castCommand).toHaveBeenLastCalledWith("tv", "seek", { seconds: 10, relative: true });
  });

  it("won't send a command with no device claimed", async () => {
    cast.onFrame({ type: "devices", devices: [dev("mpv", "mpv"), dev("tv"), me()] });
    vi.mocked(api.castCommand).mockClear();
    await expect(player.command("stop")).rejects.toThrow();
    expect(api.castCommand).not.toHaveBeenCalled();
  });

  it("ignores its own state and drops devices that leave", () => {
    cast.onFrame({
      type: "state",
      device: cast.id,
      state: state({ status: "playing", title: "Me" }),
    });
    expect(cast.states[cast.id]).toBeUndefined();

    cast.onFrame({ type: "devices", devices: [dev("mpv", "mpv"), me()] });
    expect(cast.states.tv).toBeUndefined();
  });

  it("keeps the bar up after playback finishes while the title is known", () => {
    cast.onFrame({ type: "devices", devices: [dev("mpv", "mpv", cast.id), me()] });
    cast.onFrame({
      type: "state",
      device: "mpv",
      state: state({ status: "idle", title: "Up", message: "Finished" }),
    });
    expect(player.active).toBe(true);
    cast.onFrame({ type: "state", device: "mpv", state: state({ status: "idle", title: null }) });
    expect(player.active).toBe(false);
  });
});
