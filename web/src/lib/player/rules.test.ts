import { describe, expect, it } from "vite-plus/test";
import type { Link } from "../types";
import {
  autoPick,
  currentSkip,
  orderSubtitles,
  scrubPosition,
  subtitleMatchesSource,
} from "./rules";

const link = (id: string, quality: number, extra: Partial<Link> = {}): Link => ({
  id,
  source: id,
  name: id,
  quality,
  qualityLabel: `${quality}p`,
  type: "M3U8",
  drm: false,
  playable: true,
  proxied: false,
  host: "example.com",
  order: 0,
  score: 0.5,
  successes: 0,
  failures: 0,
  ...extra,
});

describe("autoPick", () => {
  it("waits while links resolve below the auto-play priority", () => {
    expect(autoPick([link("a", 360)], undefined, new Set(), new Set(), false, false)).toBeNull();
  });

  it("takes the best link once all are in", () => {
    const picked = autoPick(
      [link("a", 360), link("b", 1080)],
      undefined,
      new Set(),
      new Set(),
      true,
      false,
    );
    expect(picked).toMatchObject({ id: "b" });
  });

  it("skips tried links on fallback and reports when none are left", () => {
    const links = [link("a", 1080), link("b", 720)];
    expect(autoPick(links, undefined, new Set(["a"]), new Set(["a"]), false, true)).toMatchObject({
      id: "b",
    });
    expect(autoPick(links, undefined, new Set(), new Set(["a", "b"]), true, true)).toBe("none");
  });
});

describe("subtitles", () => {
  it("matches source labels", () => {
    expect(subtitleMatchesSource("English (Reanime)", "Reanime", "Reanime 1080p")).toBe(true);
    expect(subtitleMatchesSource("English (Rea)", "Reanime", "Reanime")).toBe(false);
    expect(subtitleMatchesSource("English", "Reanime", "Reanime")).toBe(false);
  });

  it("puts the link's own subtitles first, the rest once done", () => {
    const subs = [
      { id: "1", lang: "English", url: "" },
      { id: "2", lang: "English (Foo)", url: "" },
    ];
    const l = link("Foo", 720);
    expect(orderSubtitles(subs, l, false).map((s) => s.id)).toEqual(["2"]);
    expect(orderSubtitles(subs, l, true).map((s) => s.id)).toEqual(["2", "1"]);
  });
});

describe("currentSkip", () => {
  it("ignores the last second of a range", () => {
    const r = { type: "op" as const, start: 10, end: 100 };
    expect(currentSkip([r], 50)).toBe(r);
    expect(currentSkip([r], 99.5)).toBeNull();
  });
});

describe("scrubPosition", () => {
  it("moves up to three minutes for a full-width drag", () => {
    expect(scrubPosition(600, 1, 1440)).toBe(780);
    expect(scrubPosition(10, -1, 1440)).toBe(0);
    expect(scrubPosition(0, 0.5, 1440)).toBeCloseTo(0.5 ** 1.6 * 180);
  });
});
