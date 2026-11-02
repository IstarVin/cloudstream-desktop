import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

const wp = vi.hoisted(() => ({
  open: true,
  item: {
    details: { name: "Dune", provider: "P", url: "u", poster: "poster.jpg" },
    data: "ep2",
  } as unknown,
  status: "playing",
  message: null as string | null,
  position: 30,
  duration: 600,
  paused: false,
  label: "E2",
  episode: undefined as { poster?: string } | undefined,
  current: { id: "l1" },
  audioTracks: [{ id: 0, label: "English", selected: true }],
  loadedSubs: [
    { id: "s1", lang: "English" },
    { id: "s2", lang: "French" },
  ],
  subtitle: "s2" as string | null,
  embeddedSubs: [{ id: 3, label: "Signs" }],
  embeddedSub: -1,
  hasNext: true,
  hasPrev: false,
  failed: [] as string[],
  skip: null,
  video: null as { volume: number; pause(): void; play(): Promise<void> } | null,
  start: vi.fn(async () => {}),
  toggle: vi.fn(),
  seekBy: vi.fn(),
  seekTo: vi.fn(),
  close: vi.fn(),
  next: vi.fn(),
  prev: vi.fn(),
  episodeAt: vi.fn(),
  skipNow: vi.fn(),
  setAudio: vi.fn(),
  selectEmbedded: vi.fn(),
  selectSubtitle: vi.fn(async () => {}),
  subtitlesOffNow: vi.fn(),
  pick: vi.fn(),
}));

vi.mock("./web-player.svelte", () => ({ webPlayer: wp }));
vi.mock("./sources.svelte", () => ({
  sources: { links: [{ id: "l7" }], subtitles: [{ id: "s9", lang: "German", url: "x" }] },
}));
vi.mock("./api", () => ({ castSocket: () => ({ send() {}, stop() {} }) }));

const { cast, defaultName, stateOf } = await import("./cast.svelte");

describe("defaultName", () => {
  it("names the browser and system", () => {
    expect(
      defaultName("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"),
    ).toBe("Firefox on Linux");
    expect(
      defaultName(
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36",
      ),
    ).toBe("Chrome on Android");
    expect(defaultName("curl/8")).toBe("Browser");
  });
});

describe("stateOf", () => {
  it("reports the web player the way mpv's state looks", () => {
    const s = stateOf(wp as never, 80);
    expect(s).toMatchObject({
      status: "playing",
      position: 30,
      duration: 600,
      volume: 80,
      title: "Dune",
      episodeLabel: "E2",
      provider: "P",
      url: "u",
      data: "ep2",
      poster: "poster.jpg",
      hasNext: true,
    });
    expect(s.audioTracks).toEqual([
      { id: 0, type: "audio", title: "English", lang: null, selected: true, external: false },
    ]);
    expect(s.subTracks.map((t) => [t.id, t.title, t.selected])).toEqual([
      [1, "English", false],
      [2, "French", true],
      [1003, "Signs", false],
    ]);
  });

  it("is idle when closed, paused when finished", () => {
    expect(stateOf({ ...wp, open: false } as never, 100).status).toBe("idle");
    expect(stateOf({ ...wp, status: "ended" } as never, 100).status).toBe("paused");
    expect(stateOf({ ...wp, status: "loading" } as never, 100).status).toBe("resolving");
  });
});

describe("receiving", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    cast.receiver = true;
  });

  it("plays a cast with its link, subtitles and position", () => {
    cast.onFrame({
      type: "play",
      request: {
        provider: "P",
        url: "u",
        data: "ep1",
        linkId: "l7",
        subtitleIds: ["s9"],
        startAt: 42,
      },
    });
    expect(wp.start).toHaveBeenCalledWith(
      { provider: "P", url: "u", data: "ep1" },
      42,
      undefined,
      ["s9"],
      "l7",
    );
  });

  it("maps commands onto the player", () => {
    const run = (cmd: string, body = {}) => cast.onFrame({ type: "command", cmd, body });
    run("toggle");
    run("seek", { seconds: -10, relative: true });
    run("seek", { seconds: 100 });
    run("watched-next");
    run("episode", { value: 3 });
    run("skip");
    run("stop");
    run("source", { linkId: "l7" });
    run("sub-add", { id: "s9" });
    expect(wp.toggle).toHaveBeenCalled();
    expect(wp.seekBy).toHaveBeenCalledWith(-10);
    expect(wp.seekTo).toHaveBeenCalledWith(100);
    expect(wp.next).toHaveBeenCalled();
    expect(wp.episodeAt).toHaveBeenCalledWith(3);
    expect(wp.skipNow).toHaveBeenCalled();
    expect(wp.close).toHaveBeenCalled();
    expect(wp.pick).toHaveBeenCalledWith({ id: "l7" });
    expect(wp.selectSubtitle).toHaveBeenCalledWith({ id: "s9", lang: "German", url: "x" });
  });

  it("selects tracks by the ids it reported", () => {
    const track = (type: string, id: string) =>
      cast.onFrame({ type: "command", cmd: "track", body: { type, id } });
    track("audio", "1");
    track("sub", "1003");
    track("sub", "1");
    track("sub", "no");
    expect(wp.setAudio).toHaveBeenCalledWith(1);
    expect(wp.selectEmbedded).toHaveBeenCalledWith(3);
    expect(wp.selectSubtitle).toHaveBeenCalledWith({ id: "s1", lang: "English", url: "" });
    expect(wp.subtitlesOffNow).toHaveBeenCalled();
  });

  it("sets the volume out of 100", () => {
    wp.video = { volume: 1, pause() {}, play: async () => {} };
    cast.onFrame({ type: "command", cmd: "volume", body: { value: 130 } });
    expect(wp.video.volume).toBe(1);
    cast.onFrame({ type: "command", cmd: "volume", body: { value: 25 } });
    expect(wp.video.volume).toBe(0.25);
  });

  it("ignores casts when receiving is off", () => {
    cast.receiver = false;
    cast.onFrame({ type: "play", request: { provider: "P", url: "u", data: "ep1" } });
    cast.onFrame({ type: "command", cmd: "stop", body: {} });
    expect(wp.start).not.toHaveBeenCalled();
    expect(wp.close).not.toHaveBeenCalled();
  });
});
