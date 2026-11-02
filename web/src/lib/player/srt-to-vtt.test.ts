import { describe, expect, it } from "vite-plus/test";
import { srtToVtt } from "./srt-to-vtt";

describe("srtToVtt", () => {
  it("adds the header and converts timestamps", () => {
    const srt =
      "﻿1\r\n00:00:01,500 --> 00:00:03,250\r\nHello\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\n{\\an8}Top\r\n";
    expect(srtToVtt(srt)).toBe(
      "WEBVTT\n\n1\n00:00:01.500 --> 00:00:03.250\nHello\n\n2\n00:00:04.000 --> 00:00:05.000\nTop\n",
    );
  });

  it("handles timestamps without hours", () => {
    expect(srtToVtt("1\n00:01,000 --> 00:02,000\nHi")).toContain("00:01.000 --> 00:02.000");
  });
});
