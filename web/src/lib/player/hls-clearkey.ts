import type Hls from "hls.js";
import type {
  HlsConfig,
  LoaderCallbacks,
  LoaderConfiguration,
  Loader,
  PlaylistLoaderContext,
} from "hls.js";

const format = "org.w3.clearkey";
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const base64url = (bytes: Uint8Array) =>
  encode(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** Read the default KIDs from protected audio/video sample entries, including CBCS tenc v1. */
export function initKeyIds(bytes: Uint8Array): Uint8Array[] {
  function boxes(data: Uint8Array, name: string): Uint8Array[] {
    const result: Uint8Array[] = [];
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    for (let at = 0; at + 8 <= data.length;) {
      const size = view.getUint32(at);
      if (size < 8 || at + size > data.length) break;
      if (String.fromCharCode(...data.subarray(at + 4, at + 8)) === name)
        result.push(data.subarray(at + 8, at + size));
      at += size;
    }
    return result;
  }
  let nodes = [bytes];
  for (const name of ["moov", "trak", "mdia", "minf", "stbl", "stsd"])
    nodes = nodes.flatMap((b) => boxes(b, name));
  return nodes.flatMap((stsd) =>
    ["encv", "enca"].flatMap((type) =>
      boxes(stsd.subarray(8), type).flatMap((entry) =>
        boxes(entry.subarray(type === "encv" ? 78 : 28), "sinf").flatMap((sinf) =>
          boxes(sinf, "schi").flatMap((schi) =>
            boxes(schi, "tenc")
              .filter((tenc) => tenc.length >= 24)
              .map((tenc) => tenc.slice(8, 24)),
          ),
        ),
      ),
    ),
  );
}

/** hls.js decrypts identity SAMPLE-AES in TS only. Use EME for fMP4 with an openly supplied key. */
export function clearKeyHls(HlsClass: typeof Hls): Partial<HlsConfig> {
  const keys = new Map<string, string>();
  const BaseLoader = HlsClass.DefaultConfig.loader as new (
    config: HlsConfig,
  ) => Loader<PlaylistLoaderContext>;
  return {
    emeEnabled: true,
    drmSystems: {
      [format]: {
        licenseUrl: "data:application/json,{}",
        generateRequest: (_type, _data, context) => ({
          initDataType: "keyids",
          initData: new TextEncoder().encode(
            JSON.stringify({
              kids: [base64url(context.decryptdata.keyId!)],
            }),
          ).buffer,
        }),
      },
    },
    licenseXhrSetup(xhr, url, context, challenge) {
      if (context.keySystem !== format) return;
      const { kids } = JSON.parse(new TextDecoder().decode(challenge)) as { kids: string[] };
      const license = JSON.stringify({
        keys: kids.map((kid) => {
          const key = keys.get(kid);
          if (!key) throw new Error("The HLS playlist has no key for this track");
          return { kty: "oct", kid, k: key };
        }),
        type: "temporary",
      });
      xhr.open("GET", `data:application/json,${encodeURIComponent(license)}`, true);
    },
    pLoader: class extends BaseLoader {
      private controller = new AbortController();
      override abort() {
        this.controller.abort();
        super.abort();
      }
      override destroy() {
        this.controller.abort();
        super.destroy();
      }
      override load(
        context: PlaylistLoaderContext,
        config: LoaderConfiguration,
        callbacks: LoaderCallbacks<PlaylistLoaderContext>,
      ) {
        super.load(context, config, {
          ...callbacks,
          onSuccess: (response, stats, ctx, details) => {
            const prepare = async () => {
              if (typeof response.data !== "string") return;
              const text = response.data;
              const lines = text.split(/\r?\n/);
              const keyLines = lines.filter(
                (l) =>
                  l.startsWith("#EXT-X-KEY:") &&
                  /METHOD=SAMPLE-AES(?:,|$)/.test(l) &&
                  (!l.includes("KEYFORMAT=") || l.includes('KEYFORMAT="identity"')),
              );
              const map = lines.find((l) => l.startsWith("#EXT-X-MAP:"));
              if (!map || !keyLines.length) return;
              // Ranged init sections and rotating keys need their own handling; leave other HLS untouched.
              if (map.includes("BYTERANGE=") || new Set(keyLines).size !== 1) return;
              const uri = (line: string) => /\bURI="([^"]+)"/.exec(line)?.[1];
              const initUrl = uri(map);
              const keyUrl = uri(keyLines[0]);
              if (!initUrl || !keyUrl) return;
              const read = async (ref: string) => {
                const res = await fetch(new URL(ref, response.url || context.url), {
                  signal: this.controller.signal,
                });
                if (!res.ok) throw new Error(`HLS key/init request failed (${res.status})`);
                return new Uint8Array(await res.arrayBuffer());
              };
              const [init, key] = await Promise.all([read(initUrl), read(keyUrl)]);
              const kids = initKeyIds(init);
              if (key.length !== 16 || !kids.length)
                throw new Error("Invalid SAMPLE-AES MP4 key or initialization segment");
              for (const kid of kids) keys.set(base64url(kid), base64url(key));
              const kid = kids[0];
              const hex = [...kid].map((b) => b.toString(16).padStart(2, "0")).join("");
              const tag = `#EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:application/octet-stream;base64,${encode(kid)}",KEYFORMAT="${format}",KEYID=0x${hex}`;
              // The init entry is protected even when the first media samples form an unencrypted lead-in.
              response.data = lines
                .flatMap((line) =>
                  keyLines.includes(line) ? [tag] : line === map ? [tag, line] : [line],
                )
                .join("\n");
            };
            void prepare()
              .then(() => {
                if (!this.controller.signal.aborted)
                  callbacks.onSuccess(response, stats, ctx, details);
              })
              .catch((error: unknown) => {
                if (!this.controller.signal.aborted)
                  callbacks.onError(
                    { code: 0, text: error instanceof Error ? error.message : String(error) },
                    ctx,
                    details,
                    stats,
                  );
              });
          },
        });
      }
    },
  };
}
