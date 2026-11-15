import { describe, expect, it } from "vitest";
import {
  buildHostMap,
  findProvider,
  hostOf,
  normalizeServerUrl,
  titleUrl,
  urlPatterns,
} from "./match";
import type { Provider } from "./types";

const provider = (name: string, mainUrl: string, installedAt: number | null = null): Provider => ({
  name,
  plugin: name,
  mainUrl,
  lang: "en",
  types: [],
  nsfw: false,
  hasMainPage: true,
  iconUrl: null,
  installedAt,
});

describe("hostOf", () => {
  it("normalizes case, www and trailing dots", () => {
    expect(hostOf("https://WWW.MkvDrama.net./tqjdjzwy8")).toBe("mkvdrama.net");
    expect(hostOf("http://mkvdrama.net:8080/x")).toBe("mkvdrama.net");
  });
  it("ignores non-web URLs", () => {
    expect(hostOf("chrome://extensions")).toBeNull();
    expect(hostOf("not a url")).toBeNull();
  });
});

describe("findProvider", () => {
  const map = buildHostMap([
    provider("MkvDrama", "https://mkvdrama.net"),
    provider("Local", "http://localhost:3000"),
  ]);
  it("matches the host, www and subdomains", () => {
    expect(findProvider(map, "https://mkvdrama.net/tqjdjzwy8")?.name).toBe("MkvDrama");
    expect(findProvider(map, "https://www.mkvdrama.net/a")?.name).toBe("MkvDrama");
    expect(findProvider(map, "https://m.mkvdrama.net/a")?.name).toBe("MkvDrama");
    expect(findProvider(map, "http://localhost:3000/a")?.name).toBe("Local");
  });
  it("doesn't match other sites or lookalikes", () => {
    expect(findProvider(map, "https://notmkvdrama.net/a")).toBeNull();
    expect(findProvider(map, "https://mkvdrama.net.evil.com/a")).toBeNull();
    expect(findProvider(map, "https://net/a")).toBeNull();
  });
});

describe("buildHostMap", () => {
  it("picks the most recently installed provider for a shared host", () => {
    const map = buildHostMap([
      provider("Dulo", "https://dulo.tv", 100),
      provider("Dulo (Other repo)", "https://www.dulo.tv", 300),
      provider("Dulo (Third)", "https://dulo.tv", 200),
    ]);
    expect(map.get("dulo.tv")?.name).toBe("Dulo (Other repo)");
  });
  it("ranks providers without an install time last, keeping list order", () => {
    const map = buildHostMap([
      provider("A", "https://a.tv"),
      provider("B", "https://a.tv"),
      provider("C", "https://c.tv"),
    ]);
    expect(map.get("a.tv")?.name).toBe("A");
    expect(
      buildHostMap([provider("A", "https://a.tv"), provider("B", "https://a.tv", 1)]).get("a.tv")
        ?.name,
    ).toBe("B");
  });
  it("skips providers without a usable mainUrl", () => {
    expect(buildHostMap([provider("Broken", ""), provider("Ftp", "ftp://x.tv")]).size).toBe(0);
  });
});

describe("urlPatterns", () => {
  it("covers subdomains, and lists IPs and single-label hosts exactly", () => {
    const map = buildHostMap([
      provider("A", "https://mkvdrama.net"),
      provider("B", "http://192.168.1.5:8080"),
      provider("C", "http://localhost"),
      provider("D", "https://xn--bcher-kva.example"),
    ]);
    expect(urlPatterns(map)).toEqual([
      "*://*.mkvdrama.net/*",
      "*://*.xn--bcher-kva.example/*",
      "*://192.168.1.5/*",
      "*://localhost/*",
    ]);
  });
});

describe("titleUrl", () => {
  it("builds the web UI's title link", () => {
    expect(
      titleUrl("https://cloudstream.example.com", "MkvDrama", "https://mkvdrama.net/tqjdjzwy8"),
    ).toBe(
      "https://cloudstream.example.com/title?provider=MkvDrama&url=https%3A%2F%2Fmkvdrama.net%2Ftqjdjzwy8",
    );
    expect(titleUrl("http://s", "Dulo (Other repo)", "https://d/x?y=1")).toBe(
      "http://s/title?provider=Dulo%20(Other%20repo)&url=https%3A%2F%2Fd%2Fx%3Fy%3D1",
    );
  });
});

describe("normalizeServerUrl", () => {
  it("adds a scheme and drops the path", () => {
    expect(normalizeServerUrl(" cloudstream.example.com/settings ")).toBe(
      "https://cloudstream.example.com",
    );
    expect(normalizeServerUrl("192.168.1.5:6969")).toBe("http://192.168.1.5:6969");
    expect(normalizeServerUrl("localhost:6969")).toBe("http://localhost:6969");
    expect(normalizeServerUrl("http://cloudstream.example.com/")).toBe(
      "http://cloudstream.example.com",
    );
  });
  it("rejects empty and non-web input", () => {
    expect(normalizeServerUrl("")).toBeNull();
    expect(normalizeServerUrl("ftp://x")).toBeNull();
  });
});
