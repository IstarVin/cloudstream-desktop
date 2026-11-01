import { describe, expect, it } from "vite-plus/test";
import { episodeLabel, formatTime, titleHref } from "./api";

describe("formatTime", () => {
  it("formats minutes and hours", () => {
    expect(formatTime(0)).toBe("0:00");
    expect(formatTime(65)).toBe("1:05");
    expect(formatTime(3725)).toBe("1:02:05");
  });

  it("treats bad input as zero", () => {
    expect(formatTime(NaN)).toBe("0:00");
    expect(formatTime(-3)).toBe("0:00");
  });
});

describe("episodeLabel", () => {
  it("combines season, episode and name", () => {
    expect(episodeLabel({ season: 1, episode: 3, name: "The Journey" }, 2)).toBe(
      "S1E3 · The Journey",
    );
    expect(episodeLabel({ season: null, episode: 7, name: null }, 6)).toBe("E7");
  });

  it("falls back to the position", () => {
    expect(episodeLabel({ season: null, episode: null, name: null }, 4)).toBe("Episode 5");
  });
});

describe("titleHref", () => {
  it("puts the provider URL in the query string", () => {
    expect(titleHref("Dulo", "https://dulo.mov/title/movie/1/?a=b&c")).toBe(
      "/title?provider=Dulo&url=https%3A%2F%2Fdulo.mov%2Ftitle%2Fmovie%2F1%2F%3Fa%3Db%26c",
    );
  });
});
