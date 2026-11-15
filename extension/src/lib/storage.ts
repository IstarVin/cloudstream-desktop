import { storage } from "wxt/utils/storage";
import type { Provider } from "./types";

/** The server's origin, as normalized by normalizeServerUrl. */
export const serverUrl = storage.defineItem<string | null>("local:serverUrl", { fallback: null });
export const serverName = storage.defineItem<string | null>("local:serverName", { fallback: null });
/** The Bearer token from the device sign-in. Never sent anywhere but [serverUrl]. */
export const token = storage.defineItem<string | null>("local:token", { fallback: null });

export interface ProviderCache {
  list: Provider[];
  fetchedAt: number;
}
export const providers = storage.defineItem<ProviderCache | null>("local:providers", {
  fallback: null,
});

/** A device sign-in waiting for approval. The background polls it; the popup only shows it. */
export interface PendingSignIn {
  userCode: string;
  deviceCode: string;
  verificationUri: string;
  interval: number;
  expiresAt: number;
  error?: string;
}
export const pendingSignIn = storage.defineItem<PendingSignIn | null>("local:pendingSignIn", {
  fallback: null,
});

/** Hosts where the user hid the page button. */
export const hiddenHosts = storage.defineItem<string[]>("local:hiddenHosts", { fallback: [] });
