<script lang="ts">
  import { Camera, ImageUp, Smartphone, X } from "@lucide/svelte";
  import { onDestroy, tick as flush } from "svelte";
  import { decodeFile, decodeQr, extractToken, pairUrl } from "../pair-scan";

  let video = $state<HTMLVideoElement>();
  let canvas = $state<HTMLCanvasElement>();
  let scanning = $state(false);
  let error = $state<string | null>(null);
  let manual = $state("");
  let stream: MediaStream | null = null;
  let raf = 0;

  // getUserMedia only exists on https or localhost; a LAN http address falls back to a photo.
  const liveCamera = typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;

  function pair(text: string): boolean {
    const token = extractToken(text);
    if (!token) return false;
    stop();
    // Relative on purpose: whatever host the QR carries, pair against the server we're already talking to.
    location.href = pairUrl(token);
    return true;
  }

  function stop() {
    cancelAnimationFrame(raf);
    stream?.getTracks().forEach((t) => t.stop());
    stream = null;
    scanning = false;
  }

  async function start() {
    error = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false,
      });
    } catch {
      error = "Couldn't open the camera. Allow access, or use a photo instead.";
      return;
    }
    scanning = true;
    // The <video> only exists once `scanning` renders.
    await flush();
    if (!video) return stop();
    video.srcObject = stream;
    await video.play().catch(() => {});
    let busy = false;
    const tick = async () => {
      if (!scanning) return;
      if (!busy && video && canvas && video.readyState >= 2) {
        busy = true;
        const text = await decodeQr(video, canvas).catch(() => null);
        busy = false;
        if (text && !pair(text)) error = "That QR code isn't a Cloudstream pairing code.";
        else if (text) return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
  }

  async function onFile(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = "";
    if (!file || !canvas) return;
    error = null;
    const text = await decodeFile(file, canvas).catch(() => null);
    if (!text) error = "No QR code found in that photo. Try again, closer and in focus.";
    else if (!pair(text)) error = "That QR code isn't a Cloudstream pairing code.";
  }

  function submitManual(e: SubmitEvent) {
    e.preventDefault();
    error = null;
    if (!pair(manual)) error = "That doesn't look like a pairing link or token.";
  }

  onDestroy(stop);
</script>

<main class="pair">
  <div class="card">
    <div class="icon">
      <Smartphone size={32} strokeWidth={1.8} aria-hidden="true" />
    </div>
    <h1>Pair this device</h1>
    <p>
      On your desktop, open Cloudstream and go to <b>Settings → Pair a phone</b>, then scan the code
      here.
    </p>

    {#if scanning}
      <div class="viewfinder">
        <!-- svelte-ignore a11y_media_has_caption -->
        <video bind:this={video} playsinline muted></video>
      </div>
      <button class="btn" onclick={stop}><X size={18} aria-hidden="true" /> Cancel</button>
    {:else}
      <div class="actions">
        {#if liveCamera}
          <button class="btn primary" onclick={start}
            ><Camera size={18} strokeWidth={1.8} aria-hidden="true" /> Scan code</button
          >
        {/if}
        <label class={["btn", { primary: !liveCamera }]}>
          <ImageUp size={18} strokeWidth={1.8} aria-hidden="true" />
          {liveCamera ? "Use a photo" : "Take a photo of the code"}
          <input type="file" accept="image/*" capture="environment" hidden onchange={onFile} />
        </label>
      </div>
      <form class="manual" onsubmit={submitManual}>
        <input
          type="text"
          placeholder="Or paste the pairing link or token"
          aria-label="Pairing link or token"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          bind:value={manual}
        />
        <button class="btn" type="submit" disabled={!manual.trim()}>Pair</button>
      </form>
    {/if}

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <canvas bind:this={canvas} hidden></canvas>

    <p class="muted">
      The code signs this browser in for a year. You can sign every device out from the desktop.
    </p>
  </div>
</main>

<style>
  .pair {
    min-height: 100vh;
    display: grid;
    place-items: center;
    padding: 24px;
  }
  .card {
    max-width: 380px;
    width: 100%;
    text-align: center;
  }
  .icon {
    width: 72px;
    height: 72px;
    margin: 0 auto 20px;
    border-radius: 20px;
    display: grid;
    place-items: center;
    background: var(--accent-soft);
    color: var(--accent);
  }
  h1 {
    font-size: 24px;
    margin-bottom: 12px;
  }
  p {
    margin: 0 0 12px;
  }
  .actions {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin: 20px 0 14px;
  }
  .viewfinder {
    margin: 20px 0 12px;
    border-radius: var(--radius-sm);
    overflow: hidden;
    background: #000;
    aspect-ratio: 1;
  }
  .viewfinder video {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .manual {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;
  }
  .manual input {
    flex: 1;
    min-width: 0;
    min-height: 44px;
    padding: 0 12px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--line);
    background: var(--surface-2);
    color: inherit;
  }
  .error {
    color: var(--danger, #e5484d);
  }
</style>
