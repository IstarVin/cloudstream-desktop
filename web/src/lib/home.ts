import { infiniteQueryOptions } from "@tanstack/svelte-query";
import { api } from "./api";
import type { HomePage, HomeRow } from "./types";

export function mergeHomeRows(pages: HomePage[]): HomeRow[] {
  const rows = new Map<string, HomeRow>();
  const seen = new Map<string, Set<string>>();
  for (const page of pages) {
    for (const row of page.rows) {
      if (!rows.has(row.name)) {
        rows.set(row.name, { ...row, items: [] });
        seen.set(row.name, new Set());
      }
      for (const item of row.items) {
        const key = JSON.stringify([item.provider, item.url]);
        if (seen.get(row.name)!.has(key)) continue;
        seen.get(row.name)!.add(key);
        rows.get(row.name)!.items.push(item);
      }
    }
  }
  return [...rows.values()];
}

export function nextHomePage(lastPage: HomePage, pages: HomePage[], lastPageParam: number) {
  if (!lastPage.hasNext) return undefined;
  // Stop providers that return empty or repeated pages, even if hasNext stays true.
  const previous = mergeHomeRows(pages.slice(0, -1));
  const hasNewItems = lastPage.rows.some((row) => {
    const seen = new Set(
      previous
        .find((r) => r.name === row.name)
        ?.items.map((i) => JSON.stringify([i.provider, i.url])),
    );
    return row.items.some((item) => !seen.has(JSON.stringify([item.provider, item.url])));
  });
  return hasNewItems ? lastPageParam + 1 : undefined;
}

export const homePagesOptions = (provider: string, section: number) =>
  infiniteQueryOptions({
    queryKey: ["home-pages", provider, section],
    queryFn: ({ pageParam }) => api.homeRow(provider, section, pageParam),
    initialPageParam: 1,
    getNextPageParam: nextHomePage,
    staleTime: 5 * 60_000,
  });
