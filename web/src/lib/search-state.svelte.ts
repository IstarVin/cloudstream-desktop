import { streamSearch } from "#lib/api";
import type { SearchItem } from "#lib/types";

/** Search results live outside the page so leaving Search and coming back keeps them (and the running search). */
class SearchState {
  results = $state<{ provider: string; items: SearchItem[] }[]>([]);
  errors = $state<{ provider: string; error: string }[]>([]);
  searching = $state(false);
  type = $state<string | null>(null);
  draft = $state("");
  private key: string | null = null;
  private stop: (() => void) | null = null;

  /** Starts a search unless this exact one is already running or finished; `force` reruns it. */
  run(q: string, provider: string, force = false): boolean {
    const key = `${provider}\n${q.trim()}`;
    if (!force && key === this.key) return false;
    this.stop?.();
    this.key = key;
    this.results = [];
    this.errors = [];
    this.searching = false;
    if (!q.trim()) return true;
    this.searching = true;
    this.stop = streamSearch(
      q.trim(),
      {
        results: (p, items) => {
          if (items.length) this.results.push({ provider: p, items });
        },
        error: (p, error) => this.errors.push({ provider: p, error }),
        done: () => (this.searching = false),
      },
      provider || undefined,
    );
    return true;
  }
}

export const searchState = new SearchState();
