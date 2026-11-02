<script lang="ts">
  import {
    Captions,
    ChevronDown,
    FastForward,
    Gauge,
    Layers,
    ListVideo,
    Maximize,
    Minimize,
    MoveHorizontal,
    Pause,
    PictureInPicture2,
    Play,
    RefreshCw,
    RectangleHorizontal,
    RotateCcw,
    SkipBack,
    SkipForward,
    Sun,
    Volume2,
    VolumeX,
    X,
    ZoomIn,
  } from "@lucide/svelte";
  import { useQueryClient } from "@tanstack/svelte-query";
  import { onDestroy, tick, untrack } from "svelte";
  import { formatTime } from "../../api";
  import { playerGestures } from "../../player/gestures";
  import { player } from "../../player.svelte";
  import { loadLocal, prefs, saveLocal, type ResizeMode } from "../../player/prefs.svelte";
  import { scrubPosition, skipName } from "../../player/rules";
  import { sources } from "../../sources.svelte";
  import { webPlayer } from "../../web-player.svelte";
  import PlayerPanel, { type Panel } from "./PlayerPanel.svelte";
  import SeekBar from "./SeekBar.svelte";

  const p = webPlayer;
  const client = useQueryClient();

  // Watch progress shown on the title and home screens is only fetched on load, so refetch it once a save lands.
  p.onProgressSaved = () => {
    client.invalidateQueries({ queryKey: ["continue"] });
    client.invalidateQueries({ queryKey: ["history"] });
    client.invalidateQueries({ queryKey: ["details"] });
  };
  onDestroy(() => (p.onProgressSaved = null));
  const pref = $derived(prefs.value);

  let root = $state<HTMLDivElement | null>(null);
  let video = $state<HTMLVideoElement | null>(null);
  let panel = $state<Panel | null>(null);
  let controls = $state(true);
  let fullscreen = $state(false);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  // Gesture feedback, as on the phone.
  let holding = $state(false);
  let rateBeforeHold = 1;
  let scrubTarget = $state<number | null>(null);
  let scrubFrom = $state(0);
  let lastScrubSeek = 0;
  let tapSeek = $state<{ side: -1 | 1; seconds: number } | null>(null);
  let tapSeekTimer: ReturnType<typeof setTimeout> | undefined;
  let level = $state<{ kind: "volume" | "brightness"; value: number } | null>(null);
  let levelTimer: ReturnType<typeof setTimeout> | undefined;
  let brightness = $state(1);

  // Fit / fill / zoom: scaling the stage keeps libass's canvas lined up with the picture.
  let box = $state({ w: 0, h: 0 });
  let natural = $state({ w: 0, h: 0 });
  const stageTransform = $derived.by(() => {
    const { w, h } = box;
    const { w: vw, h: vh } = natural;
    if (!w || !h || !vw || !vh || pref.resizeMode === "fit") return "none";
    const fit = Math.min(w / vw, h / vh);
    if (pref.resizeMode === "zoom") return `scale(${Math.max(w / vw, h / vh) / fit})`;
    return `scale(${w / (vw * fit)}, ${h / (vh * fit)})`;
  });
  const RESIZE = [
    { mode: "fit", label: "Fit", icon: RectangleHorizontal },
    { mode: "fill", label: "Stretch", icon: MoveHorizontal },
    { mode: "zoom", label: "Zoom", icon: ZoomIn },
  ] satisfies { mode: ResizeMode; label: string; icon: unknown }[];
  const resize = $derived(RESIZE.find((r) => r.mode === pref.resizeMode) ?? RESIZE[0]);

  const busy = $derived(p.status === "resolving" || p.status === "loading" || p.buffering);
  const subtitleLine = $derived(
    [p.label, p.current && `${p.current.source} ${p.current.qualityLabel}`]
      .filter(Boolean)
      .join(" · "),
  );

  $effect(() => {
    const v = video;
    if (v) untrack(() => p.bind(v));
    return () => p.unbind();
  });

  /** Controls hide after a moment while playing; any interaction shows them again. */
  function poke() {
    controls = true;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (!p.paused && !panel && p.status === "playing") controls = false;
    }, 3500);
  }
  $effect(() => {
    // Holding the video still for a swipe-seek isn't a pause worth showing the controls for.
    if (p.scrubbing) return;
    if (p.paused || panel || p.status !== "playing") {
      controls = true;
      clearTimeout(hideTimer);
    } else poke();
  });

  // The sources sheet opens on its own when auto-play is off or every link failed.
  $effect(() => {
    if (p.pickSource) untrack(openSources);
  });

  function openSources() {
    const item = p.item;
    if (!item) return;
    const d = item.details;
    sources.show({
      provider: d.provider,
      url: d.url,
      data: item.data,
      title: d.name,
      label: p.label,
    });
  }

  function flash(kind: "volume" | "brightness", value: number) {
    level = { kind, value };
    clearTimeout(levelTimer);
    levelTimer = setTimeout(() => (level = null), 800);
  }

  function setVolume(v: number) {
    if (!video) return;
    video.volume = Math.min(1, Math.max(0, v));
    if (video.volume > 0) video.muted = false;
    flash("volume", video.volume);
  }

  function tapSeekBy(side: -1 | 1) {
    const step = pref.seekStep;
    tapSeek = { side, seconds: (tapSeek?.side === side ? tapSeek.seconds : 0) + step };
    p.seekBy(side * step);
    clearTimeout(tapSeekTimer);
    tapSeekTimer = setTimeout(() => (tapSeek = null), 700);
  }

  const gestures = playerGestures({
    tap(pointer) {
      if (panel) {
        panel = null;
        return;
      }
      if (pointer === "mouse") {
        p.toggle();
        poke();
      } else if (controls && !p.paused) {
        controls = false;
        clearTimeout(hideTimer);
      } else poke();
    },
    doubleCenter(pointer) {
      if (pointer === "mouse") void toggleFullscreen();
    },
    sideTap: tapSeekBy,
    holdStart() {
      if (!video || video.paused) return;
      holding = true;
      rateBeforeHold = video.playbackRate;
      video.playbackRate = pref.holdSpeed;
    },
    holdEnd() {
      if (!holding || !video) return;
      holding = false;
      video.playbackRate = rateBeforeHold;
    },
    scrub(fraction, done) {
      if (p.duration <= 0) return;
      if (scrubTarget == null) {
        scrubFrom = p.position;
        p.beginScrub();
      }
      const target = scrubPosition(scrubFrom, fraction, p.duration);
      scrubTarget = target;
      // Seek along the way now and then, so the picture follows the finger.
      const now = Date.now();
      if (done || now - lastScrubSeek > 200) {
        lastScrubSeek = now;
        p.seekTo(target);
      }
      if (done) {
        scrubTarget = null;
        p.endScrub();
      }
    },
    vertical(side, delta, done) {
      if (done) return;
      if (side === 1 && pref.volumeGesture && video) setVolume(video.volume + delta * 1.5);
      if (side === -1 && pref.brightnessGesture) {
        brightness = Math.min(1, Math.max(0.15, brightness + delta * 1.5));
        flash("brightness", brightness);
      }
    },
    options: () => ({
      swipeSeek: pref.swipeSeek,
      vertical: pref.volumeGesture || pref.brightnessGesture,
    }),
  });

  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else {
        await root?.requestFullscreen({ navigationUI: "hide" });
        // Phones turn to landscape for the video; not every browser allows it.
        await (screen.orientation as { lock?: (o: string) => Promise<void> })
          .lock?.("landscape")
          .catch(() => {});
      }
    } catch {
      // Fullscreen refused (e.g. not from a user gesture).
    }
  }

  async function togglePip() {
    if (!video) return;
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    } catch {
      // Not supported here.
    }
  }

  function cycleResize() {
    const i = RESIZE.findIndex((r) => r.mode === pref.resizeMode);
    prefs.update({ resizeMode: RESIZE[(i + 1) % RESIZE.length].mode });
    poke();
  }

  function close() {
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
    panel = null;
    p.close();
  }

  /** Shrinks the player to a floating window that keeps playing while the app is used. */
  function minimize() {
    void setMinimized(true);
  }

  let morph: Animation | undefined;
  let morphing = $state(false);

  /** Glides the box from where it was ([first]) to where it is now ([last]). */
  function glide(el: HTMLElement, first: DOMRect, last: DOMRect, from: number, to: number) {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const frame = (r: DOMRect, radius: number) => ({
      top: `${r.top}px`,
      left: `${r.left}px`,
      width: `${r.width}px`,
      height: `${r.height}px`,
      right: "auto",
      bottom: "auto",
      borderRadius: `${radius}px`,
    });
    morphing = true;
    const run = el.animate([frame(first, from), frame(last, to)], {
      duration: 300,
      easing: "cubic-bezier(0.32, 0.72, 0, 1)",
    });
    morph = run;
    const done = () => {
      if (morph === run) morphing = false;
    };
    run.finished.then(done, done);
  }

  /** Flips between the full screen and the floating window, gliding the box from one to the other. */
  async function setMinimized(next: boolean) {
    if (!root || p.minimized === next) return;
    if (document.fullscreenElement) await document.exitFullscreen().catch(() => {});
    const el = root;
    const first = el.getBoundingClientRect();
    morph?.cancel();
    panel = null;
    p.minimized = next;
    await tick();
    glide(el, first, el.getBoundingClientRect(), next ? 0 : 12, next ? 12 : 0);
  }

  // ---- the floating window: drag it, and it settles against the nearest side edge ------------------------------

  const DOCK_KEY = "player-mini";
  const EDGE_GAP = 12;
  const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

  /** Which side edge the window sits on, and how far it has been lifted off the bottom. */
  let dock = $state<{ side: "left" | "right"; lift: number }>(
    loadLocal(DOCK_KEY, { side: "right", lift: 0 }),
  );
  let drag = $state<{ x: number; y: number } | null>(null);
  let grab: { px: number; py: number; rect: DOMRect; moved: boolean } | null = null;
  let swallowClick = false;

  function dragDown(e: PointerEvent) {
    if (e.button !== 0 || !root) return;
    morph?.cancel();
    morphing = false;
    grab = { px: e.clientX, py: e.clientY, rect: root.getBoundingClientRect(), moved: false };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function dragMove(e: PointerEvent) {
    const g = grab;
    if (!g) return;
    const dx = e.clientX - g.px;
    const dy = e.clientY - g.py;
    if (!g.moved && Math.hypot(dx, dy) < 6) return;
    g.moved = true;
    // Stay on screen while dragging.
    drag = {
      x: clamp(dx, -g.rect.left, innerWidth - g.rect.right),
      y: clamp(dy, -g.rect.top, innerHeight - g.rect.bottom),
    };
  }

  async function dragUp() {
    const g = grab;
    grab = null;
    if (!g?.moved || !root) return;
    swallowClick = true;
    setTimeout(() => (swallowClick = false), 50);
    const el = root;
    const first = el.getBoundingClientRect();
    const side = first.left + first.width / 2 < innerWidth / 2 ? "left" : "right";
    // Settle on the nearer edge, keeping the height it was dropped at (between the tab bar and the top).
    drag = null;
    dock = { side, lift: 0 };
    await tick();
    const rest = el.getBoundingClientRect();
    const lift = clamp(rest.bottom - first.bottom, 0, rest.bottom - rest.height - EDGE_GAP);
    dock = { side, lift };
    saveLocal(DOCK_KEY, dock);
    await tick();
    glide(el, first, el.getBoundingClientRect(), 12, 12);
  }

  /** A shorter screen can leave the window above the top edge; bring it back down. */
  function fitDock() {
    if (!p.minimized || !root) return;
    const over = EDGE_GAP - root.getBoundingClientRect().top;
    if (over > 0 && dock.lift > 0) dock = { ...dock, lift: Math.max(0, dock.lift - over) };
  }

  function expand() {
    if (swallowClick) return;
    void setMinimized(false);
  }

  function onKey(e: KeyboardEvent) {
    if (!p.open || p.minimized || sources.open || e.ctrlKey || e.metaKey || e.altKey) return;
    const t = e.target as HTMLElement | null;
    if (t?.closest("input, select, textarea, [contenteditable]")) return;
    const handled = () => {
      e.preventDefault();
      poke();
    };
    switch (e.key) {
      case " ":
      case "k":
        if (t?.closest("button") && e.key === " ") return;
        p.toggle();
        return handled();
      case "ArrowLeft":
        p.seekBy(-pref.seekStep);
        return handled();
      case "ArrowRight":
        p.seekBy(pref.seekStep);
        return handled();
      case "j":
        p.seekBy(-10);
        return handled();
      case "l":
        p.seekBy(10);
        return handled();
      case "ArrowUp":
        if (video) setVolume(video.volume + 0.05);
        return handled();
      case "ArrowDown":
        if (video) setVolume(video.volume - 0.05);
        return handled();
      case "m":
        if (video) video.muted = !video.muted;
        return handled();
      case "f":
        void toggleFullscreen();
        return handled();
      case "N":
        p.next();
        return handled();
      case "P":
        p.prev();
        return handled();
      case "s":
        p.skipNow();
        return handled();
      case "c":
        p.cycleSubtitle();
        return handled();
      case "Escape":
        if (panel) panel = null;
        else if (!document.fullscreenElement) minimize();
        return handled();
    }
    if (/^[0-9]$/.test(e.key) && p.duration > 0) {
      p.seekTo((Number(e.key) / 10) * p.duration);
      handled();
    }
  }

  // Leaving the tab can keep the video in a floating window (Chrome's automatic picture-in-picture).
  $effect(() => {
    if (!("mediaSession" in navigator)) return;
    try {
      navigator.mediaSession.setActionHandler(
        "enterpictureinpicture" as MediaSessionAction,
        pref.autoPip && p.open ? () => void video?.requestPictureInPicture() : null,
      );
    } catch {
      // Unsupported.
    }
  });

  // ---- subtitles: WebVTT drawn here, so size, box and position follow the settings ----------------------------

  let cues = $state<DocumentFragment[]>([]);
  let cueBox = $state<HTMLDivElement | null>(null);
  $effect(() => {
    const v = video;
    const id = p.subtitle;
    void p.loadedSubs.length;
    if (!v) return;
    let active: TextTrack | null = null;
    for (const el of v.querySelectorAll("track")) {
      const on = el.dataset.id === id && !!el.dataset.vtt;
      el.track.mode = on ? "hidden" : "disabled";
      if (on) active = el.track;
    }
    cues = [];
    if (!active) return;
    const track = active;
    const update = () =>
      (cues = [...(track.activeCues ?? [])].map((c) => (c as VTTCue).getCueAsHTML()));
    track.addEventListener("cuechange", update);
    update();
    return () => track.removeEventListener("cuechange", update);
  });
  $effect(() => {
    if (!cueBox) return;
    cueBox.replaceChildren(
      ...cues.map((f) => {
        const line = document.createElement("span");
        line.className = "cue";
        line.append(f.cloneNode(true));
        return line;
      }),
    );
  });
</script>

<svelte:window onkeydown={onKey} onpagehide={() => p.saveOnExit()} onresize={fitDock} />
<svelte:document onfullscreenchange={() => (fullscreen = !!document.fullscreenElement)} />

{#if p.open}
  <div
    class={[
      "player",
      {
        idle: !controls,
        mini: p.minimized,
        left: p.minimized && dock.side === "left",
        dragging: !!drag,
        morphing,
        "above-bar": player.active,
      },
    ]}
    bind:this={root}
    role="region"
    aria-label="Video player"
    onpointermove={(e) => e.pointerType === "mouse" && poke()}
    bind:clientWidth={box.w}
    bind:clientHeight={box.h}
    style:--sub-scale={pref.subtitleScale}
    style:--lift="{dock.lift}px"
    style:transform={drag ? `translate(${drag.x}px, ${drag.y}px)` : null}
  >
    <div
      class="stage"
      style:transform={stageTransform}
      style:filter={brightness < 1 ? `brightness(${brightness})` : null}
    >
      <!-- svelte-ignore a11y_media_has_caption -->
      <video
        bind:this={video}
        playsinline
        onloadedmetadata={(e) =>
          (natural = { w: e.currentTarget.videoWidth, h: e.currentTarget.videoHeight })}
        onresize={(e) =>
          (natural = { w: e.currentTarget.videoWidth, h: e.currentTarget.videoHeight })}
        onplaying={() => p.onVideoEvent("playing")}
        onwaiting={() => p.onVideoEvent("waiting")}
        onpause={() => p.onVideoEvent("pause")}
        onplay={() => p.onVideoEvent("play")}
        onended={() => p.onVideoEvent("ended")}
        onratechange={() => p.onVideoEvent("ratechange")}
        onvolumechange={() => p.onVideoEvent("volumechange")}
      >
        {#each p.loadedSubs as s (s.id)}
          {#if s.src}
            <track kind="subtitles" src={s.src} label={s.lang} data-id={s.id} data-vtt="1" />
          {/if}
        {/each}
      </video>
    </div>

    <div
      class={["subs", { raised: controls, boxed: pref.subtitleBackground }]}
      bind:this={cueBox}
      aria-live="off"
    ></div>

    {#if !p.minimized}<div class="touch" {@attach gestures}></div>{/if}

    {#if busy && p.status !== "error"}
      <div class="center-status" aria-live="polite">
        <div class="spinner big"></div>
        {#if p.status !== "playing" && p.message}<span>{p.message}</span>{/if}
      </div>
    {/if}

    {#if p.minimized}
      <button
        class="mini-hit"
        onclick={expand}
        onpointerdown={dragDown}
        onpointermove={dragMove}
        onpointerup={dragUp}
        onpointercancel={dragUp}
        aria-label="Expand player"
      ></button>
      <button class="mini-close" onclick={close} aria-label="Close player"
        ><X size={18} strokeWidth={2} aria-hidden="true" /></button
      >
      <div class="mini-bar">
        <div class="mini-text">
          <div class="title">{p.item?.details.name ?? ""}</div>
          <div class="sub">{p.status === "error" ? (p.message ?? "Couldn't play") : p.label}</div>
        </div>
        <button
          class="icon"
          onclick={() => p.toggle()}
          disabled={p.status === "resolving" || p.status === "error"}
          aria-label={p.status === "ended" ? "Replay" : p.paused ? "Play" : "Pause"}
        >
          {#if p.status === "ended"}<RotateCcw size={20} strokeWidth={2} aria-hidden="true" />
          {:else if p.paused}<Play
              size={20}
              fill="currentColor"
              strokeWidth={1.8}
              aria-hidden="true"
            />{:else}<Pause
              size={20}
              fill="currentColor"
              strokeWidth={1.8}
              aria-hidden="true"
            />{/if}
        </button>
      </div>
      <div class="mini-line">
        <div style:width="{p.duration > 0 ? (p.position / p.duration) * 100 : 0}%"></div>
      </div>
    {:else}
      {#if p.status === "error"}
        <div class="card error" role="alert">
          <p>{p.message ?? "Couldn't play this episode"}</p>
          <div class="row">
            <button class="btn" onclick={openSources}
              ><Layers size={18} strokeWidth={1.8} aria-hidden="true" /> Sources</button
            >
            <button class="btn" onclick={() => p.retry()}
              ><RefreshCw size={18} strokeWidth={1.8} aria-hidden="true" /> Retry</button
            >
          </div>
        </div>
      {/if}

      {#if holding}
        <div class="pill top">{pref.holdSpeed}× <FastForward size={16} aria-hidden="true" /></div>
      {/if}
      {#if scrubTarget != null}
        <div class="pill mid">
          {formatTime(scrubTarget)}
          <small
            >{scrubTarget >= scrubFrom ? "+" : "−"}{formatTime(
              Math.abs(scrubTarget - scrubFrom),
            )}</small
          >
        </div>
      {/if}
      {#if tapSeek}
        <div class={["tap-seek", tapSeek.side < 0 ? "left" : "right"]}>
          {tapSeek.side < 0 ? "−" : "+"}{tapSeek.seconds}s
        </div>
      {/if}
      {#if level}
        <div class="pill level">
          {#if level.kind === "volume"}<Volume2 size={18} aria-hidden="true" />{:else}<Sun
              size={18}
              aria-hidden="true"
            />{/if}
          <span class="meter"><span style:width="{level.value * 100}%"></span></span>
        </div>
      {/if}

      {#if p.skip}
        <button class="skip-btn" onclick={() => p.skipNow()}
          ><FastForward size={16} strokeWidth={1.8} aria-hidden="true" /> Skip {skipName(
            p.skip,
          )}</button
        >
      {/if}

      {#if p.upNext != null}
        <div class="card up-next">
          <small>Up next in {p.upNext}</small>
          <b>{p.item?.episodes[(p.item?.index ?? 0) + 1]?.name ?? "Next episode"}</b>
          <div class="row">
            <button class="btn primary" onclick={() => p.next()}>Play now</button>
            <button class="btn" onclick={() => p.cancelUpNext()}>Cancel</button>
          </div>
        </div>
      {/if}

      <div class="chrome" class:shown={controls} inert={!controls}>
        <div class="top">
          <button class="icon" onclick={minimize} aria-label="Minimize player" title="Minimize"
            ><ChevronDown size={26} strokeWidth={1.8} aria-hidden="true" /></button
          >
          <div class="titles">
            <div class="title">{p.item?.details.name ?? ""}</div>
            {#if subtitleLine}<div class="sub">{subtitleLine}</div>{/if}
          </div>
          <button
            class="action size"
            onclick={cycleResize}
            aria-label={`Video size: ${resize.label}. Change`}
            title="Video size"
            ><resize.icon size={19} strokeWidth={1.8} aria-hidden="true" /><span
              >{resize.label}</span
            ></button
          >
          {#if "pictureInPictureEnabled" in document && document.pictureInPictureEnabled}
            <button class="icon" onclick={togglePip} aria-label="Picture in picture"
              ><PictureInPicture2 size={20} strokeWidth={1.8} aria-hidden="true" /></button
            >
          {/if}
          <button
            class="icon"
            onclick={toggleFullscreen}
            aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
            >{#if fullscreen}<Minimize
                size={20}
                strokeWidth={1.8}
                aria-hidden="true"
              />{:else}<Maximize size={20} strokeWidth={1.8} aria-hidden="true" />{/if}</button
          >
        </div>

        <div class="middle">
          <button
            class="icon mid"
            onclick={() => p.prev()}
            disabled={!p.hasPrev}
            aria-label="Previous episode"
            ><SkipBack size={26} fill="currentColor" strokeWidth={1.8} aria-hidden="true" /></button
          >
          <button
            class="icon play"
            onclick={() => p.toggle()}
            disabled={p.status === "resolving" || p.status === "error"}
            aria-label={p.status === "ended" ? "Replay" : p.paused ? "Play" : "Pause"}
          >
            {#if p.status === "ended"}<RotateCcw
                size={32}
                strokeWidth={2}
                aria-hidden="true"
              />{:else if p.paused}<Play
                size={34}
                fill="currentColor"
                strokeWidth={1.8}
                aria-hidden="true"
              />{:else}<Pause
                size={34}
                fill="currentColor"
                strokeWidth={1.8}
                aria-hidden="true"
              />{/if}
          </button>
          <button
            class="icon mid"
            onclick={() => p.next()}
            disabled={!p.hasNext}
            aria-label="Next episode"
            ><SkipForward
              size={26}
              fill="currentColor"
              strokeWidth={1.8}
              aria-hidden="true"
            /></button
          >
        </div>

        <div class="bottom">
          <div class="times">
            <span>{formatTime(p.position)}</span>
            <span>−{formatTime(Math.max(0, p.duration - p.position))}</span>
          </div>
          <SeekBar
            position={p.position}
            duration={p.duration}
            buffered={p.buffered}
            skips={p.skips}
            onseek={(t) => p.seekTo(t)}
          />
          <div class="actions">
            <button
              class="icon"
              onclick={() => video && (video.muted = !video.muted)}
              aria-label={pref.muted ? "Unmute" : "Mute"}
              >{#if pref.muted || pref.volume === 0}<VolumeX
                  size={20}
                  strokeWidth={1.8}
                  aria-hidden="true"
                />{:else}<Volume2 size={20} strokeWidth={1.8} aria-hidden="true" />{/if}</button
            >
            <input
              class="volume"
              type="range"
              min="0"
              max="1"
              step="0.01"
              value={pref.muted ? 0 : pref.volume}
              aria-label="Volume"
              oninput={(e) => setVolume(Number(e.currentTarget.value))}
              onkeydown={(e) => e.stopPropagation()}
            />
            <span class="spacer"></span>
            <button class="action" onclick={() => (panel = panel === "speed" ? null : "speed")}
              ><Gauge size={19} strokeWidth={1.8} aria-hidden="true" /><span
                >{p.rate === 1 ? "Speed" : `${p.rate}×`}</span
              ></button
            >
            <button class="action" onclick={() => (panel = panel === "tracks" ? null : "tracks")}
              ><Captions size={19} strokeWidth={1.8} aria-hidden="true" /><span
                >Audio &amp; subs</span
              ></button
            >
            <button class="action" onclick={openSources}
              ><Layers size={19} strokeWidth={1.8} aria-hidden="true" /><span>Sources</span></button
            >
            {#if (p.item?.episodes.length ?? 0) > 1}
              <button
                class="action"
                onclick={() => (panel = panel === "episodes" ? null : "episodes")}
                ><ListVideo size={19} strokeWidth={1.8} aria-hidden="true" /><span>Episodes</span
                ></button
              >
            {/if}
            {#if p.hasNext}
              <button class="action" onclick={() => p.next()}
                ><SkipForward size={19} strokeWidth={1.8} aria-hidden="true" /><span>Next</span
                ></button
              >
            {/if}
          </div>
        </div>
      </div>

      {#if panel}
        <PlayerPanel {panel} onclose={() => (panel = null)} />
      {/if}
    {/if}
  </div>
{/if}

<style>
  .player {
    position: fixed;
    inset: 0;
    z-index: 70;
    background: #000;
    color: #fff;
    overflow: hidden;
    container-type: size;
    user-select: none;
    -webkit-user-select: none;
  }
  .player.idle {
    cursor: none;
  }
  .stage {
    position: absolute;
    inset: 0;
    transform-origin: center;
    transition: transform 200ms ease;
  }
  /* The box is changing size every frame; the picture must keep up, not trail behind. */
  .player.morphing .stage {
    transition: none;
  }
  video {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
  }
  .stage :global(canvas.JASSUB) {
    pointer-events: none;
  }
  .touch {
    position: absolute;
    inset: 0;
    touch-action: none;
  }

  .subs {
    position: absolute;
    left: 6%;
    right: 6%;
    bottom: 6%;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    pointer-events: none;
    text-align: center;
    font-size: calc(var(--sub-scale) * clamp(16px, 4.6cqh, 46px));
    line-height: 1.25;
    font-weight: 600;
    transition: bottom 200ms ease;
    white-space: pre-line;
  }
  .subs.raised {
    bottom: max(6%, 150px);
  }
  .subs :global(.cue) {
    padding: 0 0.2em;
    text-shadow:
      0 0 3px #000,
      0 0 3px #000,
      0 1px 2px #000,
      0 0 6px #000;
  }
  .subs.boxed :global(.cue) {
    background: rgb(0 0 0 / 0.72);
    text-shadow: none;
    border-radius: 4px;
    padding: 0.05em 0.35em;
  }
  /* Subtitles inside the stream (HLS/DASH renditions) use the browser's own renderer. */
  video::cue {
    font-size: calc(var(--sub-scale) * 100%);
  }

  .chrome {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    pointer-events: none;
    opacity: 0;
    transition: opacity 220ms ease;
    background: linear-gradient(
      to bottom,
      rgb(0 0 0 / 0.65),
      transparent 22%,
      transparent 62%,
      rgb(0 0 0 / 0.8)
    );
  }
  .chrome.shown {
    opacity: 1;
  }
  .chrome.shown > * {
    pointer-events: auto;
  }
  .top {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: max(10px, env(safe-area-inset-top)) 12px 10px;
  }
  .titles {
    flex: 1;
    min-width: 0;
    padding: 0 6px;
  }
  .title {
    font-weight: 700;
    font-size: 17px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sub {
    font-size: 13px;
    color: rgb(255 255 255 / 0.75);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .middle {
    align-self: center;
    display: flex;
    align-items: center;
    gap: clamp(20px, 8vw, 64px);
  }
  .bottom {
    padding: 0 16px max(12px, var(--safe-b));
  }
  .times {
    display: flex;
    justify-content: space-between;
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    color: rgb(255 255 255 / 0.85);
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 2px;
    flex-wrap: wrap;
  }
  .spacer {
    flex: 1;
  }
  .icon {
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    transition:
      background-color 150ms ease,
      transform 150ms ease;
  }
  .icon:hover:not(:disabled) {
    background: rgb(255 255 255 / 0.14);
  }
  .icon:active:not(:disabled) {
    transform: scale(0.92);
  }
  .icon:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .icon.mid {
    width: 56px;
    height: 56px;
  }
  .icon.play {
    width: 76px;
    height: 76px;
    background: rgb(0 0 0 / 0.35);
  }
  .action {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 40px;
    padding: 0 12px;
    border-radius: 999px;
    color: #fff;
    font-size: 13.5px;
    font-weight: 600;
    transition: background-color 150ms ease;
  }
  .action:hover {
    background: rgb(255 255 255 / 0.14);
  }
  .volume {
    width: 90px;
    accent-color: var(--accent);
  }

  .center-status {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    pointer-events: none;
    font-size: 14px;
    color: rgb(255 255 255 / 0.85);
    text-align: center;
    padding: 16px;
  }
  .spinner.big {
    width: 44px;
    height: 44px;
    border-width: 3px;
  }
  .card {
    position: absolute;
    z-index: 3;
    background: rgb(22 24 30 / 0.94);
    border: 1px solid var(--line);
    border-radius: 14px;
    padding: 16px;
  }
  .card .row {
    display: flex;
    gap: 8px;
    margin-top: 12px;
  }
  .card.error {
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    width: min(380px, calc(100% - 32px));
    text-align: center;
  }
  .card.error p {
    margin: 0;
  }
  .card.error .row {
    justify-content: center;
  }
  .up-next {
    right: 16px;
    bottom: 120px;
    width: min(300px, calc(100% - 32px));
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .up-next small {
    color: var(--muted);
  }
  .btn.primary {
    background: var(--accent);
    color: var(--accent-ink);
  }

  .pill {
    position: absolute;
    left: 50%;
    transform: translateX(-50%);
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 8px 14px;
    border-radius: 999px;
    background: rgb(0 0 0 / 0.65);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    z-index: 2;
  }
  .pill.top {
    top: 70px;
  }
  .pill.mid {
    top: 40%;
    font-size: 22px;
  }
  .pill.mid small {
    font-size: 14px;
    color: rgb(255 255 255 / 0.75);
  }
  .pill.level {
    top: 70px;
  }
  .meter {
    display: block;
    width: 110px;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.25);
    overflow: hidden;
  }
  .meter span {
    display: block;
    height: 100%;
    background: #fff;
  }
  .tap-seek {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    padding: 12px 18px;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.16);
    font-weight: 700;
    font-size: 18px;
    pointer-events: none;
  }
  .tap-seek.left {
    left: 12%;
  }
  .tap-seek.right {
    right: 12%;
  }
  .skip-btn {
    position: absolute;
    right: 16px;
    bottom: 130px;
    z-index: 3;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 10px 16px;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.92);
    color: #000;
    font-weight: 700;
  }

  /* Minimised: a floating window above the tab bar, so the app stays usable underneath. */
  .player.mini {
    inset: auto 12px calc(var(--nav-h) + var(--safe-b) + 12px + var(--lift, 0px)) auto;
    width: min(320px, calc(100vw - 24px));
    aspect-ratio: 16 / 9;
    border-radius: 12px;
    box-shadow:
      0 0 0 1px var(--line),
      0 12px 32px rgb(0 0 0 / 0.55);
    cursor: default;
  }
  .player.mini.above-bar {
    bottom: calc(var(--nav-h) + var(--bar-h) + var(--safe-b) + 20px + var(--lift, 0px));
  }
  .player.mini.left {
    left: 12px;
    right: auto;
  }
  .player.mini.dragging {
    cursor: grabbing;
  }
  .player.mini .subs {
    bottom: 22%;
    font-size: calc(var(--sub-scale) * 11px);
    transition: none;
  }
  .player.mini .center-status span {
    display: none;
  }
  .player.mini .spinner.big {
    width: 28px;
    height: 28px;
    border-width: 2px;
  }
  .mini-hit {
    position: absolute;
    inset: 0;
    cursor: grab;
    touch-action: none;
  }
  .player.dragging .mini-hit {
    cursor: grabbing;
  }
  .mini-close {
    position: absolute;
    top: 6px;
    right: 6px;
    width: 32px;
    height: 32px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    background: rgb(0 0 0 / 0.55);
    backdrop-filter: blur(6px);
    transition: background-color 150ms ease;
  }
  .mini-close:hover {
    background: rgb(0 0 0 / 0.8);
  }
  .mini-bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 3px;
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 14px 4px 0 10px;
    background: linear-gradient(to top, rgb(0 0 0 / 0.8), transparent);
    pointer-events: none;
  }
  .mini-bar > * {
    pointer-events: auto;
  }
  .mini-text {
    flex: 1;
    min-width: 0;
    pointer-events: none;
  }
  .mini-text .title {
    font-size: 13px;
  }
  .mini-text .sub {
    font-size: 11.5px;
  }
  .mini-bar .icon {
    width: 40px;
    height: 40px;
  }
  .mini-line {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: rgb(255 255 255 / 0.25);
    pointer-events: none;
  }
  .mini-line > div {
    height: 100%;
    background: var(--accent);
  }

  @media (min-width: 900px) {
    .player.mini {
      right: 20px;
      bottom: calc(20px + var(--lift, 0px));
      width: 360px;
    }
    .player.mini.left {
      left: 20px;
      right: auto;
    }
    .player.mini.above-bar {
      bottom: calc(var(--bar-h) + 28px + var(--lift, 0px));
    }
  }
  @media (max-width: 700px) {
    .action:not(.size) span {
      display: none;
    }
    .volume {
      display: none;
    }
    .icon.play {
      width: 64px;
      height: 64px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .stage,
    .chrome,
    .subs {
      transition: none;
    }
    .icon:active:not(:disabled) {
      transform: none;
    }
  }
</style>
