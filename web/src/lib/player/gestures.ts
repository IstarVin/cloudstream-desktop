/** What the touch surface reports, like the phone's `Modifier.playerGestures`. */
export interface GestureHandlers {
  /** A single tap or click, once it can't be a double tap any more. */
  tap(pointer: string): void;
  /** A double tap or double click in the middle. */
  doubleCenter(pointer: string): void;
  /** Each quick tap after the first on a side: -1 left (back), 1 right (forward). */
  sideTap(side: -1 | 1): void;
  holdStart(): void;
  holdEnd(): void;
  /** A horizontal drag, as a fraction of the width; [done] on release. */
  scrub(fraction: number, done: boolean): void;
  /** A vertical touch drag: side -1 left, 1 right; [delta] is the fraction of the height moved up since last call. */
  vertical(side: -1 | 1, delta: number, done: boolean): void;
  options(): { swipeSeek: boolean; vertical: boolean };
}

const TAP_WINDOW = 300;
const HOLD_AFTER = 500;
const SLOP = 12;
/** Drags starting this close to the top or bottom edge are left to the system (and the controls). */
const EDGE = 48;

/** Pointer gestures on the video: taps, multi-tap seeking, hold for speed, horizontal scrubbing, vertical swipes. */
export function playerGestures(h: GestureHandlers) {
  return (node: HTMLElement) => {
    let start: { x: number; y: number; id: number; pointer: string } | null = null;
    let mode: "none" | "hold" | "scrub" | "vertical" | "cancelled" = "none";
    let holdTimer: ReturnType<typeof setTimeout> | undefined;
    let tapTimer: ReturnType<typeof setTimeout> | undefined;
    let lastTap = { at: 0, side: 0 as -1 | 0 | 1, count: 0 };
    let lastY = 0;
    let verticalSide: -1 | 1 = 1;

    const sideOf = (x: number): -1 | 0 | 1 => {
      const r = node.getBoundingClientRect();
      const f = (x - r.left) / r.width;
      return f < 0.38 ? -1 : f > 0.62 ? 1 : 0;
    };

    const down = (e: PointerEvent) => {
      if (e.button !== 0 || start) return;
      start = { x: e.clientX, y: e.clientY, id: e.pointerId, pointer: e.pointerType };
      mode = "none";
      lastY = e.clientY;
      holdTimer = setTimeout(() => {
        if (mode !== "none") return;
        mode = "hold";
        clearTimeout(tapTimer);
        navigator.vibrate?.(10);
        h.holdStart();
      }, HOLD_AFTER);
    };

    const move = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return;
      const dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      const r = node.getBoundingClientRect();
      if (mode === "none" && Math.hypot(dx, dy) > SLOP) {
        clearTimeout(holdTimer);
        const opts = h.options();
        const nearEdge = start.y - r.top < EDGE || r.bottom - start.y < EDGE;
        if (Math.abs(dx) > Math.abs(dy) && opts.swipeSeek) mode = "scrub";
        else if (
          Math.abs(dy) > Math.abs(dx) &&
          opts.vertical &&
          start.pointer !== "mouse" &&
          !nearEdge
        ) {
          mode = "vertical";
          verticalSide = start.x - r.left < r.width / 2 ? -1 : 1;
        } else mode = "cancelled";
        if (mode !== "cancelled") node.setPointerCapture(e.pointerId);
      }
      if (mode === "scrub") h.scrub(dx / r.width, false);
      else if (mode === "vertical") {
        h.vertical(verticalSide, (lastY - e.clientY) / r.height, false);
        lastY = e.clientY;
      }
    };

    const up = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return;
      clearTimeout(holdTimer);
      const s = start;
      start = null;
      const r = node.getBoundingClientRect();
      if (mode === "hold") return h.holdEnd();
      if (mode === "scrub") return h.scrub((e.clientX - s.x) / r.width, true);
      if (mode === "vertical") return h.vertical(verticalSide, 0, true);
      if (mode !== "none" || e.type === "pointercancel") return;

      const side = sideOf(s.x);
      const now = Date.now();
      const quick = now - lastTap.at < TAP_WINDOW && side === lastTap.side;
      lastTap = { at: now, side, count: quick ? lastTap.count + 1 : 1 };
      if (quick) {
        clearTimeout(tapTimer);
        if (side === 0) h.doubleCenter(s.pointer);
        else h.sideTap(side);
        return;
      }
      clearTimeout(tapTimer);
      tapTimer = setTimeout(() => h.tap(s.pointer), TAP_WINDOW);
    };

    node.addEventListener("pointerdown", down);
    node.addEventListener("pointermove", move);
    node.addEventListener("pointerup", up);
    node.addEventListener("pointercancel", up);
    return () => {
      clearTimeout(holdTimer);
      clearTimeout(tapTimer);
      node.removeEventListener("pointerdown", down);
      node.removeEventListener("pointermove", move);
      node.removeEventListener("pointerup", up);
      node.removeEventListener("pointercancel", up);
    };
  };
}
