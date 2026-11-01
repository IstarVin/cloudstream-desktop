import { describe, expect, it } from "vite-plus/test";
import shared from "../../../testdata/ranking-cases.json";
import { compareLinks, sourceRecord } from "./ranking";
import type { Link } from "./types";

const link = (l: (typeof shared.cases)[number]["links"][number]): Link => ({
  ...l,
  source: "S",
  name: l.id,
  qualityLabel: "",
  type: "M3U8",
  drm: false,
  proxied: false,
  host: "",
  successes: 0,
  failures: 0,
});

describe("link ranking", () => {
  // The server's RankingTest reads the same cases.
  for (const c of shared.cases) {
    it(`matches the server: ${c.name}`, () => {
      expect(
        c.links
          .map(link)
          .sort(compareLinks)
          .map((l) => l.id),
      ).toEqual(c.expected);
    });
  }

  it("describes a source's record", () => {
    expect(sourceRecord({ successes: 0, failures: 0 })).toBe("new");
    expect(sourceRecord({ successes: 8, failures: 2 })).toBe("worked 8 of 10");
  });
});
