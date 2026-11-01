import type { Link, SourceProfile } from "./types";

/** Qualities.Unknown: an adaptive "Auto" stream. */
export const AUTO_QUALITY = 400;

/** In enum order, so nearest-quality ties match Cloudstream's Kotlin implementation. */
export const QUALITIES = [
  { value: 400, label: "Auto / unknown", priority: 4 },
  { value: 144, label: "144p", priority: 0 },
  { value: 240, label: "240p", priority: 2 },
  { value: 360, label: "360p", priority: 3 },
  { value: 480, label: "480p", priority: 4 },
  { value: 720, label: "720p", priority: 5 },
  { value: 1080, label: "1080p", priority: 6 },
  { value: 1440, label: "1440p", priority: 7 },
  { value: 2160, label: "2160p / 4K", priority: 8 },
];

export function linkPriority(
  link: Pick<Link, "source" | "quality">,
  profile?: SourceProfile,
): number {
  const nearest = QUALITIES.reduce((best, q) =>
    Math.abs(q.value - link.quality) < Math.abs(best.value - link.quality) ? q : best,
  );
  return (
    (profile?.sourcePriorities[link.source] ?? 1) +
    (profile?.qualityPriorities[nearest.value] ?? nearest.priority)
  );
}

export function linkVisible(
  link: Link,
  profile: SourceProfile | undefined,
  failed: Set<string>,
  current?: string | null,
): boolean {
  return (
    link.id === current ||
    ((!profile?.hideNegativeSources || linkPriority(link, profile) >= 0) &&
      (!profile?.hideFailedSources || !failed.has(link.id)))
  );
}

export function groupLinks(links: Link[]) {
  const by = new Map<string, Link[]>();
  for (const link of links) by.set(link.source, [...(by.get(link.source) ?? []), link]);
  return [...by.entries()].map(([source, links]) => ({ source, links }));
}

/**
 * The server's link order (`LinkRanking` in LinkService.kt), so the sheet lists links in the order auto-play tries
 * them: playable first, then profile priority, quality ("Auto" just under 1080p), source success history, then
 * arrival order. `testdata/ranking-cases.json` keeps the two in step.
 */
export function compareLinks(a: Link, b: Link, profile?: SourceProfile): number {
  const q = (l: Link) => (l.quality === AUTO_QUALITY ? 1079 : l.quality);
  return (
    Number(b.playable) - Number(a.playable) ||
    (profile ? linkPriority(b, profile) - linkPriority(a, profile) : 0) ||
    q(b) - q(a) ||
    b.score - a.score ||
    a.order - b.order
  );
}

/** A source's record for the sheet: "worked 8 of 10", or "new" before it has played. */
export function sourceRecord(l: Pick<Link, "successes" | "failures">): string {
  const total = l.successes + l.failures;
  return total ? `worked ${l.successes} of ${total}` : "new";
}
