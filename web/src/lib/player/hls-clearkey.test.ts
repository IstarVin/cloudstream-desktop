import { afterEach, describe, expect, it, vi } from "vite-plus/test";
import type Hls from "hls.js";
import type { LoaderCallbacks, LoaderResponse, PlaylistLoaderContext } from "hls.js";
import { clearKeyHls, initKeyIds } from "./hls-clearkey";

const kid = Uint8Array.from({ length: 16 }, (_, i) => i + 1);
const key = Uint8Array.from({ length: 16 }, (_, i) => 255 - i);
const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const b64url = (b: Uint8Array) => b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
function join(...parts: Uint8Array[]) {
  return new Uint8Array(parts.flatMap((p) => [...p]));
}
function box(name: string, ...parts: Uint8Array[]): Uint8Array {
  const data = join(...parts);
  const out = new Uint8Array(8 + data.length);
  new DataView(out.buffer).setUint32(0, out.length);
  out.set(new TextEncoder().encode(name), 4);
  out.set(data, 8);
  return out;
}
function init(type = "encv", version = 1) {
  let data = box(
    type,
    new Uint8Array(type === "encv" ? 78 : 28),
    box("sinf", box("schi", box("tenc", new Uint8Array([version, 0, 0, 0, 0, 0x19, 1, 0]), kid))),
  );
  data = box("stsd", new Uint8Array([0, 0, 0, 0, 0, 0, 0, 1]), data);
  for (const name of ["stbl", "minf", "mdia", "trak", "moov"]) data = box(name, data);
  return data;
}
const playlist =
  '#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:4,\nclear.mp4\n' +
  '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin",KEYFORMAT="identity",IV=0x1234\n#EXTINF:4,\nencrypted.mp4\n#EXT-X-ENDLIST';

function setup(text = playlist) {
  class FakeLoader {
    load(
      context: PlaylistLoaderContext,
      _config: unknown,
      callbacks: LoaderCallbacks<PlaylistLoaderContext>,
    ) {
      callbacks.onSuccess({ data: text, url: context.url }, {} as never, context, null);
    }
    abort() {}
    destroy() {}
  }
  const config = clearKeyHls({ DefaultConfig: { loader: FakeLoader } } as unknown as typeof Hls);
  const loader = new config.pLoader!({} as never);
  const load = () =>
    new Promise<LoaderResponse>((resolve, reject) =>
      loader.load(
        { url: "https://server.example/proxy/media.m3u8" } as PlaylistLoaderContext,
        {} as never,
        { onSuccess: resolve, onError: reject, onTimeout: () => reject(new Error("timeout")) },
      ),
    );
  return { config, loader, load };
}
afterEach(() => vi.unstubAllGlobals());

describe("SAMPLE-AES fMP4 in HLS", () => {
  it("reads audio and video KIDs for CENC and CBCS and rejects truncated boxes", () => {
    expect(initKeyIds(init())).toEqual([kid]);
    expect(initKeyIds(init("enca", 0))).toEqual([kid]);
    expect(initKeyIds(init().slice(0, -1))).toEqual([]);
    expect(initKeyIds(new Uint8Array(8))).toEqual([]);
  });

  it("configures ClearKey before a protected init even with clear lead-in segments", async () => {
    const fetcher = vi.fn(
      async (url: URL) =>
        new Response(Uint8Array.from(url.pathname.endsWith("key.bin") ? key : init()).buffer),
    );
    vi.stubGlobal("fetch", fetcher);
    const { config, load } = setup();
    const result = await load();
    const text = result.data as string;
    expect(text.indexOf('KEYFORMAT="org.w3.clearkey"')).toBeLessThan(text.indexOf("#EXT-X-MAP"));
    expect(text).not.toContain('KEYFORMAT="identity"');
    expect(text).toContain("KEYID=0x0102030405060708090a0b0c0d0e0f10");
    expect(text).toContain("clear.mp4");
    expect(fetcher).toHaveBeenCalledTimes(2);
    const open = vi.fn();
    await config.licenseXhrSetup!.call(
      {} as never,
      { open } as never,
      "",
      { keySystem: "org.w3.clearkey" } as never,
      new TextEncoder().encode(JSON.stringify({ kids: [b64url(kid)] })),
    );
    const license = JSON.parse(decodeURIComponent(open.mock.calls[0][1].split(",")[1]));
    expect(license).toEqual({
      keys: [{ kty: "oct", kid: b64url(kid), k: b64url(key) }],
      type: "temporary",
    });
  });

  it("leaves TS encryption and plain HLS alone", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const ts = playlist.replace('#EXT-X-MAP:URI="init.mp4"\n', "");
    expect((await setup(ts).load()).data).toBe(ts);
    const plain = "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST";
    expect((await setup(plain).load()).data).toBe(plain);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("reports failed key requests instead of leaving playback hanging", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("", { status: 403 })),
    );
    await expect(setup().load()).rejects.toMatchObject({
      code: 0,
      text: "HLS key/init request failed (403)",
    });
  });

  it("cancels extra fetches when the stream is destroyed", async () => {
    let signal: AbortSignal | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: URL, options: RequestInit) => {
        signal = options.signal!;
        return new Promise(() => {});
      }),
    );
    const { load, loader } = setup();
    void load();
    loader.destroy();
    expect(signal?.aborted).toBe(true);
  });
});
