/** SubRip to WebVTT for the browser's `<track>`: a header, `.` for the millisecond comma, no cue numbers needed. */
export function srtToVtt(srt: string): string {
  const body = srt
    .replace(/^﻿/, "")
    .replace(/\r\n?/g, "\n")
    .replace(/(\d{1,2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2")
    // Some files leave out the hours.
    .replace(/(^|\s)(\d{2}:\d{2}),(\d{3})(?=\s)/gm, "$1$2.$3")
    // SRT's {\an8}-style position tags show as text in VTT.
    .replace(/\{\\[^}]*\}/g, "")
    .trim();
  return `WEBVTT\n\n${body}\n`;
}
