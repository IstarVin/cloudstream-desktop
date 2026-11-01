import { describe, expect, it } from "vite-plus/test";
import shared from "../../../testdata/ranking-cases.json";
import priorities from "../../../testdata/source-priority-cases.json";
import { compareLinks, linkPriority, linkVisible, sourceRecord } from "./ranking";
import type { Link, SourceProfile } from "./types";

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
  for (const c of priorities.cases) {
    it(`matches Cloudstream profiles and server: ${c.name}`, () => {
      const overrides = c.profile as Partial<SourceProfile>;
      const profile: SourceProfile = {
        id: 1,
        name: "Profile 1",
        sourcePriorities: {},
        qualityPriorities: {},
        hideFailedSources: false,
        hideNegativeSources: false,
        ...overrides,
      };
      const links = c.links.map((l, i) => ({
        ...link({ ...l, score: 0.5, order: i, playable: true }),
        source: l.source,
      }));
      links.forEach((l, i) => expect(linkPriority(l, profile)).toBe(c.links[i].priority));
      expect(links.sort((a, b) => compareLinks(a, b, profile)).map((l) => l.id)).toEqual(
        c.expected,
      );
      const drm = { ...links[0], id: "drm", playable: false };
      expect([...links, drm].sort((a, b) => compareLinks(a, b, profile)).at(-1)?.id).toBe("drm");
    });
  }

  it("hides failed and negative links while keeping the current stream and zero priority", () => {
    const l = link({ id: "a", quality: 720, score: 0.5, order: 0, playable: true });
    const profile: SourceProfile = {
      id: 1,
      name: "Test",
      sourcePriorities: { S: -6 },
      qualityPriorities: {},
      hideFailedSources: true,
      hideNegativeSources: true,
    };
    expect(linkVisible(l, profile, new Set())).toBe(false);
    expect(linkVisible(l, profile, new Set([l.id]), l.id)).toBe(true);
    profile.sourcePriorities.S = -5;
    expect(linkVisible(l, profile, new Set())).toBe(true);
    expect(linkVisible(l, profile, new Set([l.id]))).toBe(false);
  });
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
