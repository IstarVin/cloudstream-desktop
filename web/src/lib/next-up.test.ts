import { describe, expect, it } from "vite-plus/test";
import shared from "../../../testdata/next-up-cases.json";
import { defaultDub, nextUp } from "./next-up";
import type { Episode } from "./types";

describe("next up", () => {
  for (const c of shared.cases) {
    it(c.name, () => {
      const eps = c.episodes.map(
        (e) =>
          ({
            data: e.data,
            name: null,
            season: null,
            episode: null,
            poster: null,
            description: null,
            date: null,
            runtime: null,
            dub: "dub" in e ? e.dub : null,
            progress:
              "progress" in e && e.progress ? { ...e.progress, duration: 1500, updated: 0 } : null,
          }) as Episode,
      );
      const dub = defaultDub(eps);
      expect(nextUp(eps.filter((e) => !dub || e.dub === dub))?.data ?? null).toBe(c.expected);
    });
  }
});
