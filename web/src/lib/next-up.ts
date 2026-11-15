import type { Episode } from "./types";

// Mirrors NextUp in shared/src/main/kotlin/desktop/player/Rules.kt; testdata/next-up-cases.json keeps the two in step.

/** The audio version shown by default: "Subbed" when there is one, else the first; null when episodes have none. */
export function defaultDub(episodes: Episode[]): string | null {
  const dubs = [...new Set(episodes.map((e) => e.dub).filter((x): x is string => !!x))];
  return dubs.find((x) => x === "Subbed") ?? dubs[0] ?? null;
}

/** The episode to resume: the last one in progress, else the first unwatched after the last watched one. */
export function nextUp(eps: Episode[]): Episode | undefined {
  const inProgress = eps.filter(
    (e) => e.progress && !e.progress.watched && e.progress.position > 30,
  );
  if (inProgress.length) return inProgress[inProgress.length - 1];
  let lastWatched = -1;
  eps.forEach((e, i) => e.progress?.watched && (lastWatched = i));
  return eps[lastWatched + 1] ?? eps[0];
}
