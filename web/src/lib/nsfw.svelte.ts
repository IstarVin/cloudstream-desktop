import { loadLocal, saveLocal } from "./player/prefs.svelte";

const KEY = "content-prefs";

/**
 * This device's "show NSFW" choice, sent with every API request. `null` until the user picks one: the device then
 * sends nothing and follows the server's default (`show_nsfw`), which is also what an older client gets.
 */
class ContentPrefs {
  showNsfw = $state<boolean | null>(
    ((v) => (typeof v === "boolean" ? v : null))(
      loadLocal(KEY, { showNsfw: null as unknown }).showNsfw,
    ),
  );

  setShowNsfw(on: boolean | null) {
    this.showNsfw = on;
    saveLocal(KEY, { showNsfw: on });
  }

  /** The `X-Show-NSFW` value for requests, or `null` to leave the choice to the server. */
  get flag(): "1" | "0" | null {
    return this.showNsfw === null ? null : this.showNsfw ? "1" : "0";
  }
}

export const content = new ContentPrefs();
