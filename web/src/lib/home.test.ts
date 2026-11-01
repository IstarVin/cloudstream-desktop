import { describe, expect, it } from "vite-plus/test";
import { mergeHomeRows, nextHomePage } from "./home";
import type { HomePage, SearchItem } from "./types";

const item = (url: string, provider = "Demo"): SearchItem => ({
  provider,
  url,
  name: url,
  type: "Movie",
  poster: null,
});
const page = (items: SearchItem[], hasNext = true): HomePage => ({
  rows: [{ name: "Featured", horizontal: false, items }],
  hasNext,
});

describe("home pagination", () => {
  it("merges matching rows in page order and removes overlapping titles", () => {
    const first = page([item("one"), item("two")]);
    const second = page([item("two"), item("three")]);
    expect(mergeHomeRows([first, second])[0].items.map((i) => i.url)).toEqual([
      "one",
      "two",
      "three",
    ]);
    expect(first.rows[0].items).toHaveLength(2);
  });

  it("keeps rows, poster orientation and provider identities separate", () => {
    const first = page([item("one"), item("one", "Other")]);
    first.rows.push({ name: "Trailers", horizontal: true, items: [item("one")] });
    const merged = mergeHomeRows([first]);
    expect(merged.map((r) => [r.name, r.horizontal, r.items.length])).toEqual([
      ["Featured", false, 2],
      ["Trailers", true, 1],
    ]);
  });

  it("advances using the last successful page number", () => {
    const first = page([item("one")]);
    const second = page([item("one"), item("two")]);
    expect(nextHomePage(first, [first], 1)).toBe(2);
    expect(nextHomePage(second, [first, second], 2)).toBe(3);
  });

  it("stops on final, empty or repeated pages even when hasNext is inaccurate", () => {
    const first = page([item("one")]);
    for (const last of [page([item("two")], false), page([]), page([item("one")])]) {
      expect(nextHomePage(last, [first, last], 2)).toBeUndefined();
    }
  });

  it("continues when another row still contributes new titles", () => {
    const first = page([item("one")]);
    const second = page([item("one")]);
    second.rows.push({ name: "Trailers", horizontal: true, items: [item("one")] });
    expect(nextHomePage(second, [first, second], 2)).toBe(3);
  });
});
