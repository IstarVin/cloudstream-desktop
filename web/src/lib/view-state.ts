// Per-URL scroll positions and per-tab last URLs, so moving around feels like a native app:
// returning to a screen puts you back where you left it.
const scrolls = new Map<string, number>();
const tabUrls = new Map<string, string>();

export const urlKey = (url: URL) => url.pathname + url.search;

export function saveScroll(url: URL) {
  scrolls.set(urlKey(url), window.scrollY);
}

/** Restores the saved scroll of `url` (or the top), waiting briefly for cached content to lay out. */
export function restoreScroll(url: URL) {
  const y = scrolls.get(urlKey(url)) ?? 0;
  const start = performance.now();
  const attempt = () => {
    window.scrollTo(0, y);
    const reached = Math.abs(window.scrollY - y) < 2;
    if (!reached && performance.now() - start < 1200) requestAnimationFrame(attempt);
  };
  attempt();
}

export function rememberTab(tab: string, url: URL) {
  tabUrls.set(tab, urlKey(url));
}

export const tabUrl = (tab: string) => tabUrls.get(tab) ?? tab;

/** Home's content-type filter, kept for the session (the chosen source is already in localStorage). */
export const homeFilter = { contentType: "all" };
