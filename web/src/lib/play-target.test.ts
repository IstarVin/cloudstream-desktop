import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

const cast = vi.hoisted(() => ({
  targets: [] as { id: string; name: string; kind: string; receiver: boolean }[],
  device(id: string) {
    return this.targets.find((d) => d.id === id);
  },
}));

vi.mock("./cast.svelte", () => ({ cast }));
vi.mock("./api", () => ({ api: { status: vi.fn() } }));

const { playTarget } = await import("./play-target.svelte");

const tv = { id: "tv1", name: "Living room", kind: "tv", receiver: true };

describe("playTarget", () => {
  beforeEach(() => {
    playTarget.saved = null;
    playTarget.status = { mpvAvailable: true, loopback: true };
    cast.targets = [];
  });

  it("plays in this browser until a device is picked, even on the server's own screen", () => {
    expect(playTarget.effective).toBe("browser");
    expect(playTarget.remote).toBe(false);
  });

  it("keeps the picked device and plays there", () => {
    cast.targets = [tv];
    playTarget.select("tv1");
    expect(playTarget.effective).toBe("tv1");
    expect(playTarget.remote).toBe(true);
    expect(playTarget.label).toBe("Living room");
    expect(playTarget.kind).toBe("tv");
  });

  it("falls back to the browser while the picked device is away, and returns when it is back", () => {
    playTarget.select("tv1");
    expect(playTarget.effective).toBe("browser");
    expect(playTarget.saved).toBe("tv1");
    cast.targets = [tv];
    expect(playTarget.effective).toBe("tv1");
  });

  it("falls back from mpv when the server has none", () => {
    playTarget.select("mpv");
    expect(playTarget.effective).toBe("mpv");
    playTarget.status = { mpvAvailable: false, loopback: true };
    expect(playTarget.effective).toBe("browser");
  });
});
