import { browser } from "wxt/browser";
import type { DeviceInfo } from "./types";

/** What the popup shows; derived by the background from storage. */
export interface State {
  serverUrl: string | null;
  serverName: string | null;
  signedIn: boolean;
  /** Whether "access to all sites" is granted (Firefox asks for it separately). */
  hostAccess: boolean;
  providerCount: number;
  providersFetchedAt: number | null;
  pending: { userCode: string; verificationUri: string; expiresAt: number; error?: string } | null;
}

export interface MatchResult {
  provider: { name: string; iconUrl: string | null } | null;
  hidden: boolean;
  signedIn: boolean;
}

export type Result<T = object> = ({ ok: true } & T) | { ok: false; error: string };

/** Requests to the background, and what each answers. */
export interface Messages {
  state: { req: object; res: State };
  connect: { req: { url: string }; res: Result<{ state: State }> };
  disconnect: { req: object; res: State };
  signIn: { req: object; res: Result };
  cancelSignIn: { req: object; res: State };
  signOut: { req: object; res: State };
  refresh: { req: object; res: Result<{ state: State }> };
  match: { req: { url: string }; res: MatchResult };
  open: { req: { url: string }; res: Result };
  devices: { req: object; res: Result<{ devices: DeviceInfo[] }> };
  play: { req: { url: string; device: string | null }; res: Result<{ message: string }> };
  /** Hides or shows again the page button on a page URL's site. */
  hide: { req: { url: string; hidden: boolean }; res: object };
}

export type Message = { [K in keyof Messages]: { type: K } & Messages[K]["req"] }[keyof Messages];

export function send<K extends keyof Messages>(
  type: K,
  req: Messages[K]["req"],
): Promise<Messages[K]["res"]> {
  return browser.runtime.sendMessage({ type, ...req });
}
