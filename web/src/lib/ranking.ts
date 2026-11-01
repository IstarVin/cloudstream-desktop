import type { Link } from "./types";

/** Qualities.Unknown: an adaptive "Auto" stream. */
export const AUTO_QUALITY = 400;

/**
 * The server's link order (`LinkRanking` in LinkService.kt), so the sheet lists links in the order auto-play tries
 * them: playable first, then quality ("Auto" counts as just under 1080p), then the source's success history, then
 * arrival order. `testdata/ranking-cases.json` keeps the two in step.
 */
export function compareLinks(a: Link, b: Link): number {
  const q = (l: Link) => (l.quality === AUTO_QUALITY ? 1079 : l.quality);
  return (
    Number(b.playable) - Number(a.playable) || q(b) - q(a) || b.score - a.score || a.order - b.order
  );
}

/** A source's record for the sheet: "worked 8 of 10", or "new" before it has played. */
export function sourceRecord(l: Pick<Link, "successes" | "failures">): string {
  const total = l.successes + l.failures;
  return total ? `worked ${l.successes} of ${total}` : "new";
}
