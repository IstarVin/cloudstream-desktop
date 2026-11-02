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
const dev = (id: string, kind: DeviceInfo["kind"] = "tv"): DeviceInfo => ({
  id,
  name: id.toUpperCase(),
  kind,
  receiver: true,
});

describe("player store (the remote)", () => {
  it("follows whichever other device is playing, mpv first", () => {
    cast.onFrame({ type: "devices", devices: [dev("mpv", "mpv"), dev("tv"), dev(cast.id, "web")] });
    expect(player.active).toBe(false);
    expect(player.device).toBe(null);

    cast.onFrame({
      type: "state",
      device: "tv",
      state: state({ status: "playing", title: "Dune" }),
    });
    expect(player.device).toBe("tv");
    expect(player.state.title).toBe("Dune");

    cast.onFrame({
      type: "state",
      device: "mpv",
      state: state({ status: "resolving", title: "Up" }),
    });
    expect(player.device).toBe("mpv");
  });

  it("keeps to the device cast to, and sends commands there", async () => {
    player.follow("tv");
    expect(player.device).toBe("tv");
    await player.command("seek", { seconds: 10, relative: true });
    expect(api.castCommand).toHaveBeenLastCalledWith("tv", "seek", { seconds: 10, relative: true });
  });

  it("ignores its own state and drops devices that leave", () => {
    cast.onFrame({
      type: "state",
      device: cast.id,
      state: state({ status: "playing", title: "Me" }),
    });
    expect(cast.states[cast.id]).toBeUndefined();

    cast.onFrame({ type: "devices", devices: [dev("mpv", "mpv"), dev(cast.id, "web")] });
    expect(cast.states.tv).toBeUndefined();
    expect(player.device).toBe("mpv");
  });

  it("keeps the bar up after playback finishes while the title is known", () => {
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
