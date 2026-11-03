import jsQR from "jsqr";

/**
 * Pulls the pairing token out of whatever was scanned or pasted. The desktop's QR holds a full
 * `http://<lan-ip>:<port>/pair?token=…` link, but that host may not be the one this browser reached the
 * server by (other interface, port-forward, hostname), so only the token is used; pairing always goes
 * back to the current origin.
 */
export function extractToken(text: string): string | null {
  const s = text.trim();
  if (!s) return null;
  try {
    const t = new URL(s).searchParams.get("token");
    if (t) return t;
  } catch {
    // not a URL
  }
  const m = s.match(/[?&]token=([^&#\s]+)/);
  if (m) return decodeURIComponent(m[1]);
  return /^[0-9a-f]{32,}$/i.test(s) ? s : null;
}

/** Same-origin pairing URL: the server sets the cookie and redirects home. */
export const pairUrl = (token: string) => `/pair?token=${encodeURIComponent(token)}`;

type Detector = { detect(src: ImageBitmapSource): Promise<{ rawValue: string }[]> };

function nativeDetector(): Detector | null {
  const Ctor = (globalThis as { BarcodeDetector?: new (o: { formats: string[] }) => Detector })
    .BarcodeDetector;
  try {
    return Ctor ? new Ctor({ formats: ["qr_code"] }) : null;
  } catch {
    return null;
  }
}

/** Decodes a QR from pixels: the browser's BarcodeDetector when present, jsQR otherwise. */
export async function decodeQr(
  source: HTMLVideoElement | HTMLImageElement | ImageBitmap,
  canvas: HTMLCanvasElement,
): Promise<string | null> {
  const native = nativeDetector();
  if (native) {
    try {
      const hits = await native.detect(source);
      if (hits[0]?.rawValue) return hits[0].rawValue;
      return null;
    } catch {
      // fall through to jsQR
    }
  }
  const w = source instanceof HTMLVideoElement ? source.videoWidth : source.width;
  const h = source instanceof HTMLVideoElement ? source.videoHeight : source.height;
  if (!w || !h) return null;
  // Downscale big photos: jsQR is slow on 12MP frames and doesn't need them.
  const scale = Math.min(1, 1000 / Math.max(w, h));
  canvas.width = Math.round(w * scale);
  canvas.height = Math.round(h * scale);
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
  const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
  return jsQR(img.data, img.width, img.height, { inversionAttempts: "dontInvert" })?.data ?? null;
}

export async function decodeFile(file: File, canvas: HTMLCanvasElement): Promise<string | null> {
  const bmp = await createImageBitmap(file);
  try {
    return await decodeQr(bmp, canvas);
  } finally {
    bmp.close();
  }
}
