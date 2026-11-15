import type {
  AuthResult,
  AuthStatus,
  DeviceAuthorization,
  DeviceInfo,
  PlayTitleRequest,
  Provider,
} from "./types";

/** The server rejected the token (expired, revoked or signed out everywhere). */
export class SignedOut extends Error {
  constructor(message = "Signed out. Sign in again.") {
    super(message);
  }
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/** A client for one server. Only the background script makes requests, so the token never reaches a web page. */
export class Api {
  constructor(
    readonly base: string,
    private readonly token: string | null = null,
    private readonly fetcher: typeof fetch = (...args) => fetch(...args),
  ) {}

  private async request<T>(
    path: string,
    init: { method?: string; body?: unknown } = {},
  ): Promise<T> {
    // The extension only acts on the site the user is already on, so it sees NSFW providers too: otherwise an NSFW
    // site would never match, and playing from it would fail on a device that keeps NSFW hidden.
    const headers: Record<string, string> = { Accept: "application/json", "X-Show-NSFW": "1" };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (init.body !== undefined) headers["Content-Type"] = "application/json";
    let res: Response;
    try {
      res = await this.fetcher(this.base + path, {
        method: init.method ?? (init.body === undefined ? "GET" : "POST"),
        headers,
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      throw new ApiError(`Can't reach ${this.base}`, 0);
    }
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      // Not JSON: a proxy error page, or not a Cloudstream server at all.
    }
    if (res.ok) {
      if (json === null && text)
        throw new ApiError(`${this.base} isn't a Cloudstream server`, res.status);
      return json as T;
    }
    const serverError = (json as { error?: unknown } | null)?.error;
    const message =
      typeof serverError === "string" && serverError
        ? serverError
        : `${res.status} ${res.statusText}`.trim();
    if (res.status === 401 && this.token) throw new SignedOut();
    throw new ApiError(message, res.status);
  }

  status() {
    return this.request<AuthStatus>("/api/auth/status");
  }
  startDeviceSignIn(deviceName: string) {
    return this.request<DeviceAuthorization>("/api/auth/device", { body: { deviceName } });
  }
  pollDeviceSignIn(deviceCode: string) {
    return this.request<AuthResult>("/api/auth/device/poll?client=extension", {
      body: { deviceCode },
    });
  }
  logout() {
    return this.request<unknown>("/api/auth/logout", { method: "POST" });
  }
  providers() {
    return this.request<Provider[]>("/api/providers");
  }
  devices() {
    return this.request<DeviceInfo[]>("/api/cast/devices");
  }
  playTitle(req: PlayTitleRequest) {
    return this.request<{ ok: boolean; title?: string }>("/api/play-title", { body: req });
  }
}
