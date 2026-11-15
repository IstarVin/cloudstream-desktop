import type { Provider } from "./types";

/** A site's host for matching: lowercased, without a trailing dot or a leading `www.`. */
export function normalizeHost(host: string): string {
  const h = host.toLowerCase().replace(/\.$/, "");
  return h.startsWith("www.") ? h.slice(4) : h;
}

/** The matching host of an http(s) URL, or null for anything else. */
export function hostOf(url: string): string | null {
  try {
    const u = new URL(url);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return u.hostname ? normalizeHost(u.hostname) : null;
  } catch {
    return null;
  }
}

/**
 * Maps each provider site's host to the provider that handles it. When providers share a host (the same plugin from two
 * repos), the most recently installed wins; providers without an install time (dev mode) rank last, in list order.
 */
export function buildHostMap(providers: Provider[]): Map<string, Provider> {
  const map = new Map<string, Provider>();
  for (const p of providers) {
    const host = hostOf(p.mainUrl);
    if (!host) continue;
    const current = map.get(host);
    if (!current || (p.installedAt ?? -Infinity) > (current.installedAt ?? -Infinity))
      map.set(host, p);
  }
  return map;
}

/** The provider for a page URL: its host, or the nearest parent domain that is a provider host. */
export function findProvider(map: Map<string, Provider>, url: string): Provider | null {
  let host = hostOf(url);
  while (host) {
    const p = map.get(host);
    if (p) return p;
    const dot = host.indexOf(".");
    host = dot < 0 ? null : host.slice(dot + 1);
    if (host && !host.includes(".")) break; // stop before a bare TLD
  }
  return null;
}

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;
const HOSTNAME = /^[a-z0-9-]+(\.[a-z0-9-]+)*$/;

/** Context-menu `targetUrlPatterns` covering every provider host and its subdomains. Invalid hosts are skipped. */
export function urlPatterns(map: Map<string, Provider>): string[] {
  const patterns: string[] = [];
  for (const host of map.keys()) {
    if (!HOSTNAME.test(host)) continue;
    // `*.host` also matches the host itself, but not for IP addresses or single-label names.
    patterns.push(IPV4.test(host) || !host.includes(".") ? `*://${host}/*` : `*://*.${host}/*`);
  }
  return patterns.sort();
}

/** The web UI's title page for a provider URL. */
export function titleUrl(server: string, provider: string, url: string): string {
  return `${server}/title?provider=${encodeURIComponent(provider)}&url=${encodeURIComponent(url)}`;
}

/** The server's origin from what the user typed: adds a scheme when missing and drops any path. */
export function normalizeServerUrl(input: string): string | null {
  const raw = input.trim();
  if (!raw) return null;
  const local = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1)/.test(raw);
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw)
    ? raw
    : `${local ? "http" : "https"}://${raw}`;
  try {
    const u = new URL(withScheme);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return u.origin;
  } catch {
    return null;
  }
}
