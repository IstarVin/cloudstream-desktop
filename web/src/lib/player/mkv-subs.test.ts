import { describe, expect, it } from "vite-plus/test";
import { cueText } from "./mkv-subs";

describe("cueText", () => {
  it("drops ASS override tags and turns \\N into line breaks", () => {
    expect(cueText("{\\an8}Top\\Nsecond {\\i1}line{\\i0}\r\n")).toBe("Top\nsecond line");
  });

  it("keeps the tags WebVTT understands", () => {
    expect(cueText("<i>Line 0</i> café")).toBe("<i>Line 0</i> café");
  });
});
