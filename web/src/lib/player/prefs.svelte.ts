/** Reads a JSON value saved on this device; storage can be missing or blocked, so failures give the fallback. */
export function loadLocal<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw == null ? fallback : { ...fallback, ...JSON.parse(raw) };
  } catch {
    return fallback;
  }
}

export function saveLocal(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Not saved; the setting still applies until the page reloads.
  }
}

export type ResizeMode = "fit" | "fill" | "zoom";

/** The browser player's settings, kept per device like the phone app's `PlayerPrefs`. */
export interface PlayerPrefs {
  /** Seconds a double tap or arrow key seeks. */
  seekStep: number;
  /** Speed while the video is held. */
  holdSpeed: number;
  /** Subtitle text size, relative to the default. */
  subtitleScale: number;
  /** Draw subtitles on a dark box instead of with an outline. */
  subtitleBackground: boolean;
  swipeSeek: boolean;
  volumeGesture: boolean;
  brightnessGesture: boolean;
  resizeMode: ResizeMode;
  volume: number;
  muted: boolean;
  /** Keep playing in a picture-in-picture window when the tab is hidden. */
  autoPip: boolean;
  /** Show total duration instead of time remaining beside the seek bar. */
  showTotalDuration: boolean;
}

const defaults: PlayerPrefs = {
  seekStep: 10,
  holdSpeed: 2,
  subtitleScale: 1,
  subtitleBackground: false,
  swipeSeek: true,
  volumeGesture: true,
  brightnessGesture: true,
  resizeMode: "fit",
  volume: 1,
  muted: false,
  autoPip: false,
  showTotalDuration: false,
};

const KEY = "player-prefs";

class Prefs {
  value = $state<PlayerPrefs>(loadLocal(KEY, defaults));

  update(patch: Partial<PlayerPrefs>) {
    this.value = { ...this.value, ...patch };
    saveLocal(KEY, this.value);
  }
}

export const prefs = new Prefs();
