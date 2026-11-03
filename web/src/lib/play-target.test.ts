import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

type Dev = { id: string; name: string; kind: string; receiver: boolean; controller?: string | null };
const cast = vi.hoisted(() => ({
  id: "me",
  targets: [] as Dev[],
  get devices() {
    return this.targets;
  },
  onDevices: null as (() => void) | null,
  claim: vi.fn(),
  device(id: string) {
    return this.targets.find((d) => d.id === id);
  },
  takenByOther(id: string) {
    const c = this.device(id)?.controller;
    return !!c && c !== this.id;
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
    cast.claim.mockClear();
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

  it("claims the device it picks, and releases it when going back to the browser", () => {
    cast.targets = [tv];
    playTarget.select("tv1");
    expect(cast.claim).toHaveBeenLastCalledWith("tv1");
    playTarget.select("browser");
    expect(cast.claim).toHaveBeenLastCalledWith(null);
  });

  it("greys out a device another device is playing on, and won't pick it", () => {
    cast.targets = [{ ...tv, controller: "other" }];
    const opt = playTarget.options.find((o) => o.id === "tv1");
    expect(opt).toMatchObject({ disabled: true, busy: true });
    playTarget.select("tv1");
    expect(playTarget.saved).toBeNull();
    expect(cast.claim).not.toHaveBeenCalled();
  });

  it("keeps a device it holds enabled", () => {
    cast.targets = [{ ...tv, controller: "me" }];
    expect(playTarget.options.find((o) => o.id === "tv1")).toMatchObject({ disabled: false, busy: false });
  });

  it("goes back to the browser when the picked device closes", () => {
    cast.targets = [tv];
    playTarget.select("tv1");
    cast.targets = [];
    expect(playTarget.effective).toBe("browser");
    playTarget.reconcile();
    expect(playTarget.saved).toBe("browser");
    expect(cast.claim).toHaveBeenLastCalledWith(null);
  });

  it("goes back to the browser when another device took the picked one", () => {
    cast.targets = [tv];
    playTarget.select("tv1");
    cast.targets = [{ ...tv, controller: "other" }];
    expect(playTarget.effective).toBe("browser");
    playTarget.reconcile();
    expect(playTarget.saved).toBe("browser");
  });

  it("claims its saved device again once the connection is back", () => {
    playTarget.saved = "tv1";
    cast.targets = [tv];
    playTarget.reconcile();
    expect(cast.claim).toHaveBeenLastCalledWith("tv1");
  });

  it("falls back from mpv when the server has none", () => {
    playTarget.select("mpv");
    expect(playTarget.effective).toBe("mpv");
    playTarget.status = { mpvAvailable: false, loopback: true };
    expect(playTarget.effective).toBe("browser");
  });
});
