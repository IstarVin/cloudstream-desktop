import { compareLinks, linkPriority, linkVisible } from "../ranking";
import type { Link, SkipRange, SourceProfile, Subtitle } from "../types";

/** The priority at which auto-play stops waiting for more links (`SourceProfile.AUTO_PLAY_PRIORITY`). */
export const AUTO_PLAY_PRIORITY = 10;

/** Progress rules shared with the mpv session and the phone's player (`Watched` in Rules.kt). */
export const WATCHED_FRACTION = 0.9;
export const PREFETCH_FRACTION = 0.85;
export const SUCCESS_AFTER_SECONDS = 15;

/** As in Cloudstream: on fallback, once every link is in, or as soon as one reaches the auto-play priority. */
export const autoPlayReady = (fallback: boolean, done: boolean, bestPriority: number) =>
  fallback || done || bestPriority >= AUTO_PLAY_PRIORITY;

/** Playable links the profile shows, best first. */
export function rankedLinks(
  links: Link[],
  profile: SourceProfile | undefined,
  failed: Set<string>,
  current?: string | null,
): Link[] {
  return links
    .filter((l) => l.playable && linkVisible(l, profile, failed, current))
    .sort((a, b) => compareLinks(a, b, profile));
}

/** The link auto-play takes now, `null` to keep waiting, or `"none"` when nothing is left to try. */
export function autoPick(
  links: Link[],
  profile: SourceProfile | undefined,
  failed: Set<string>,
  tried: Set<string>,
  done: boolean,
  fallback: boolean,
): Link | null | "none" {
  const candidates = rankedLinks(links, profile, failed).filter((l) => !tried.has(l.id));
  const best = candidates[0];
  if (best && autoPlayReady(fallback, done, linkPriority(best, profile))) return best;
  return done && !best ? "none" : null;
}

const sourceLabel = /[([]([^()[\]]+)[)\]]\s*$/;

/** Source labels are supplied by some aggregate providers as a suffix, e.g. "English (Reanime)". */
export function subtitleMatchesSource(lang: string, source: string, name: string): boolean {
  const label = sourceLabel.exec(lang)?.[1]?.trim().toLowerCase();
  if (!label) return false;
  return [source, name].some((v) => {
    const value = v.toLowerCase();
    if (value === label) return true;
    const after = value.charAt(label.length);
    return value.startsWith(label) && !!after && !/[\p{L}\p{N}]/u.test(after);
  });
}

/** In a preferred language (English for now), by label. */
export const preferredLanguage = (lang: string) =>
  ["en", "eng", "english"].some((p) => lang.toLowerCase().startsWith(p));

/** English first, then this link's source within each language group; other sources only once done. */
export function orderSubtitles(
  subs: Subtitle[],
  link: Link | null | undefined,
  done: boolean,
): Subtitle[] {
  const matching = link
    ? subs.filter((s) => subtitleMatchesSource(s.lang, link.source, link.name))
    : [];
  const ordered = done ? [...matching, ...subs.filter((s) => !matching.includes(s))] : matching;
  return ordered.sort(
    (a, b) => Number(preferredLanguage(b.lang)) - Number(preferredLanguage(a.lang)),
  );
}

/** The range [pos] is inside, ignoring its last second so a seek to the end doesn't count. */
export const currentSkip = (ranges: SkipRange[], pos: number) =>
  ranges.find((r) => pos >= r.start && pos < r.end - 1) ?? null;

export const isWatched = (position: number, duration: number) =>
  duration > 0 && position >= duration * WATCHED_FRACTION;

/**
 * Where a horizontal drag of [fraction] of the width lands. The curve is gentle near the start, so short drags move
 * a few seconds and a full-width drag moves up to three minutes (or the whole of a short video).
 */
export function scrubPosition(from: number, fraction: number, duration: number): number {
  const span = Math.max(30, Math.min(duration, 180));
  const offset = Math.sign(fraction) * Math.min(Math.abs(fraction), 1.2) ** 1.6 * span;
  return Math.min(Math.max(from + offset, 0), Math.max(duration, 0));
}

export const skipName = (r: SkipRange) =>
  ({ op: "opening", ed: "ending", recap: "recap" })[r.type] ?? "segment";
