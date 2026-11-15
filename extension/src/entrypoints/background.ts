import { browser, type Browser } from "wxt/browser";
import { defineBackground } from "wxt/utils/define-background";
import { Api, ApiError, SignedOut } from "../lib/api";
import {
  buildHostMap,
  findProvider,
  hostOf,
  normalizeServerUrl,
  titleUrl,
  urlPatterns,
} from "../lib/match";
import type { Message, Messages, State } from "../lib/messages";
import * as store from "../lib/storage";
import type { DeviceInfo, Provider } from "../lib/types";

const MENU_OPEN = "open";
const PROVIDER_REFRESH_MINUTES = 30;
const DEVICE_REFRESH_MINUTES = 1;
const ALL_SITES = { origins: ["<all_urls>"] };

export default defineBackground(() => {
  // ---- context ------------------------------------------------------------------------------------------------------

  let hostMap: { fetchedAt: number; map: Map<string, Provider> } | null = null;
  let devices: DeviceInfo[] = [];

  async function api(): Promise<Api> {
    const base = await store.serverUrl.getValue();
    if (!base) throw new ApiError("Set your server in the Cloudstream popup first", 0);
    return new Api(base, await store.token.getValue());
  }

  async function providerMap(): Promise<Map<string, Provider>> {
    const cache = await store.providers.getValue();
    if (!cache) return new Map();
    if (hostMap?.fetchedAt !== cache.fetchedAt)
      hostMap = { fetchedAt: cache.fetchedAt, map: buildHostMap(cache.list) };
    return hostMap.map;
  }

  async function providerFor(url: string): Promise<Provider | null> {
    return findProvider(await providerMap(), url);
  }

  async function signedIn() {
    return !!(await store.serverUrl.getValue()) && !!(await store.token.getValue());
  }

  async function state(): Promise<State> {
    const [serverUrl, serverName, token, providers, pending] = await Promise.all([
      store.serverUrl.getValue(),
      store.serverName.getValue(),
      store.token.getValue(),
      store.providers.getValue(),
      store.pendingSignIn.getValue(),
    ]);
    return {
      serverUrl,
      serverName,
      signedIn: !!serverUrl && !!token,
      hostAccess: await browser.permissions.contains(ALL_SITES),
      providerCount: (await providerMap()).size,
      providersFetchedAt: providers?.fetchedAt ?? null,
      pending: pending && {
        userCode: pending.userCode,
        verificationUri: pending.verificationUri,
        expiresAt: pending.expiresAt,
        error: pending.error,
      },
    };
  }

  /** Forgets the session (and the server too, with [server]). */
  async function clearSession(server = false) {
    await Promise.all([
      store.token.removeValue(),
      store.providers.removeValue(),
      store.pendingSignIn.removeValue(),
    ]);
    if (server) await Promise.all([store.serverUrl.removeValue(), store.serverName.removeValue()]);
    hostMap = null;
    devices = [];
    await rebuildMenus();
    await updateAllBadges();
  }

  /** Runs [block], turning a rejected token into a signed-out state. */
  async function authed<T>(block: (api: Api) => Promise<T>): Promise<T> {
    try {
      return await block(await api());
    } catch (e) {
      if (e instanceof SignedOut) await clearSession();
      throw e;
    }
  }

  async function refreshProviders() {
    if (!(await signedIn())) return;
    const list = await authed((a) => a.providers());
    await store.providers.setValue({ list, fetchedAt: Date.now() });
    await rebuildMenus();
    await updateAllBadges();
  }

  async function refreshDevices(): Promise<boolean> {
    if (!(await signedIn())) return false;
    const next = await authed((a) => a.devices());
    const changed = JSON.stringify(next) !== JSON.stringify(devices);
    devices = next;
    if (changed) await rebuildMenus();
    return changed;
  }

  const receivers = () => devices.filter((d) => d.receiver);

  // ---- device sign-in -----------------------------------------------------------------------------------------------

  let polling = false;
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  /** Polls a pending sign-in until it is approved, fails or expires. Only the background polls: a code works once. */
  async function pollSignIn() {
    if (polling) return;
    polling = true;
    try {
      for (;;) {
        const pending = await store.pendingSignIn.getValue();
        if (!pending || pending.error) return;
        if (Date.now() > pending.expiresAt) {
          await store.pendingSignIn.setValue({
            ...pending,
            error: "The code expired. Start again.",
          });
          return;
        }
        await sleep(Math.max(pending.interval, 2) * 1000);
        const current = await store.pendingSignIn.getValue();
        if (current?.deviceCode !== pending.deviceCode) continue;
        let result;
        try {
          result = await (await api()).pollDeviceSignIn(pending.deviceCode);
        } catch (e) {
          if (e instanceof ApiError && e.status === 0) continue; // offline for a moment; keep trying until it expires
          await store.pendingSignIn.setValue({ ...pending, error: errorText(e) });
          return;
        }
        if (result.status === "approved" && result.token) {
          await store.token.setValue(result.token);
          await store.pendingSignIn.removeValue();
          await refreshProviders().catch(warn);
          await refreshDevices().catch(warn);
          return;
        }
      }
    } finally {
      polling = false;
    }
  }

  // ---- actions ------------------------------------------------------------------------------------------------------

  async function openTitle(url: string, from?: Browser.tabs.Tab) {
    const provider = await providerFor(url);
    if (!provider) throw new Error("This site isn't one of your providers");
    const server = await store.serverUrl.getValue();
    if (!server) throw new Error("Set your server in the Cloudstream popup first");
    await browser.tabs.create({
      url: titleUrl(server, provider.name, url),
      ...(from?.id !== undefined && { openerTabId: from.id, index: from.index + 1 }),
    });
  }

  async function play(url: string, device: string | null): Promise<string> {
    const provider = await providerFor(url);
    if (!provider) throw new Error("This site isn't one of your providers");
    const result = await authed((a) => a.playTitle({ provider: provider.name, url, device }));
    const id = device ?? "mpv";
    const target = devices.find((d) => d.id === id)?.name ?? id;
    return `Playing ${result.title ?? "it"} on ${target}`;
  }

  // ---- context menus ------------------------------------------------------------------------------------------------

  let menuQueue: Promise<void> = Promise.resolve();

  function createMenu(props: Browser.contextMenus.CreateProperties) {
    return new Promise<void>((resolve) =>
      browser.contextMenus.create(props, () => {
        const error = browser.runtime.lastError;
        if (error) warn(error.message);
        resolve();
      }),
    );
  }

  /** Rebuilds the menus for links to provider sites. Serialized, so overlapping rebuilds can't create duplicate ids. */
  function rebuildMenus() {
    menuQueue = menuQueue.then(buildMenus).catch(warn);
    return menuQueue;
  }

  async function buildMenus() {
    await browser.contextMenus.removeAll();
    if (!(await signedIn())) return;
    const targetUrlPatterns = urlPatterns(await providerMap());
    if (!targetUrlPatterns.length) return;
    const link = { contexts: ["link"] as ["link"], targetUrlPatterns };
    await createMenu({ id: MENU_OPEN, title: "Open in Cloudstream", ...link });
  }

  browser.contextMenus.onClicked.addListener((info, tab) => {
    const url = info.linkUrl;
    if (!url) return;
    const id = String(info.menuItemId);
    if (id === MENU_OPEN)
      openTitle(url, tab).catch((e) => notify("Couldn't open in Cloudstream", errorText(e)));
  });

  // ---- badge --------------------------------------------------------------------------------------------------------

  async function updateBadge(tabId: number, url: string | undefined) {
    const provider = url && (await signedIn()) ? await providerFor(url) : null;
    await browser.action.setBadgeText({ tabId, text: provider ? "▶" : "" });
    await browser.action.setTitle({
      tabId,
      title: provider ? `Cloudstream · ${provider.name}` : "Cloudstream",
    });
  }

  async function updateAllBadges() {
    for (const tab of await browser.tabs.query({}))
      if (tab.id !== undefined) await updateBadge(tab.id, tab.url).catch(() => {});
  }

  browser.tabs.onUpdated.addListener((tabId, change, tab) => {
    if (change.url || change.status === "complete") updateBadge(tabId, tab.url).catch(() => {});
  });

  // ---- messages -----------------------------------------------------------------------------------------------------

  async function handle(
    msg: Message,
    sender: Browser.runtime.MessageSender,
  ): Promise<Messages[keyof Messages]["res"]> {
    switch (msg.type) {
      case "state":
        void pollSignIn();
        return state();
      case "connect": {
        const url = normalizeServerUrl(msg.url);
        if (!url)
          return {
            ok: false,
            error: "Enter the server's address, like https://cloudstream.example.com",
          };
        const status = await new Api(url).status();
        if (url !== (await store.serverUrl.getValue())) await clearSession(true);
        await store.serverUrl.setValue(url);
        await store.serverName.setValue(status.serverName);
        if (!status.configured)
          return {
            ok: false,
            error: "This server's sign-in service isn't set up yet. Finish the server setup first.",
          };
        return { ok: true, state: await state() };
      }
      case "disconnect":
        await authed((a) => a.logout()).catch(() => {});
        await clearSession(true);
        return state();
      case "signIn": {
        const name = `Browser extension (${import.meta.env.FIREFOX ? "Firefox" : "Chrome"})`;
        const auth = await (await api()).startDeviceSignIn(name);
        await store.pendingSignIn.setValue({
          userCode: auth.userCode,
          deviceCode: auth.deviceCode,
          verificationUri: auth.verificationUri,
          interval: auth.interval,
          expiresAt: Date.now() + auth.expiresIn * 1000,
        });
        void pollSignIn();
        return { ok: true };
      }
      case "cancelSignIn":
        await store.pendingSignIn.removeValue();
        return state();
      case "signOut":
        await authed((a) => a.logout()).catch(() => {});
        await clearSession();
        return state();
      case "refresh":
        await refreshProviders();
        await refreshDevices().catch(warn);
        return { ok: true, state: await state() };
      case "match": {
        const host = hostOf(msg.url);
        const provider = await providerFor(msg.url);
        return {
          provider: provider && { name: provider.name, iconUrl: provider.iconUrl },
          hidden: !!host && (await store.hiddenHosts.getValue()).includes(host),
          signedIn: await signedIn(),
        };
      }
      case "open":
        await openTitle(msg.url, sender.tab);
        return { ok: true };
      case "devices":
        await refreshDevices();
        return { ok: true, devices: receivers() };
      case "play":
        return { ok: true, message: await play(msg.url, msg.device) };
      case "hide": {
        // Normalized as in "match", so the host hidden is the host checked.
        const host = hostOf(msg.url);
        if (!host) return {};
        const hidden = (await store.hiddenHosts.getValue()).filter((h) => h !== host);
        await store.hiddenHosts.setValue(msg.hidden ? [...hidden, host] : hidden);
        return {};
      }
    }
  }

  browser.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    handle(msg as Message, sender).then(sendResponse, (e) =>
      sendResponse({ ok: false, error: errorText(e) }),
    );
    return true; // answered asynchronously
  });

  // ---- lifecycle ----------------------------------------------------------------------------------------------------

  browser.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === "providers") refreshProviders().catch(warn);
    if (alarm.name === "devices") refreshDevices().catch(warn);
  });

  browser.runtime.onInstalled.addListener(async ({ reason }) => {
    // First run: the popup in a tab, where granting site access and signing in don't close it.
    if (reason === "install")
      await browser.tabs.create({ url: browser.runtime.getURL("/popup.html") });
  });

  async function start() {
    await browser.alarms.create("providers", { periodInMinutes: PROVIDER_REFRESH_MINUTES });
    await browser.alarms.create("devices", { periodInMinutes: DEVICE_REFRESH_MINUTES });
    await browser.action.setBadgeBackgroundColor({ color: "#FF8A4C" });
    await browser.action.setBadgeTextColor?.({ color: "#1D0E05" });
    await rebuildMenus();
    void pollSignIn();
    await refreshProviders().catch(warn);
    await refreshDevices().catch(warn);
  }
  start().catch(warn);
});

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function warn(e: unknown) {
  console.warn("[cloudstream]", e);
}

async function notify(title: string, message: string) {
  await browser.notifications.create({
    type: "basic",
    iconUrl: browser.runtime.getURL("/icon/128.png"),
    title,
    message,
  });
}
