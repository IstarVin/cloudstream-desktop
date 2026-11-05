import { scryptAsync } from "@noble/hashes/scrypt.js";

interface Env {
  AUTH: DurableObjectNamespace;
  ASSETS: Fetcher;
  OWNER_USERNAME?: string;
  OWNER_PASSWORD?: string;
  SERVER_SECRET: string;
}
interface Account {
  username: string;
  salt: string;
  hash: string;
  epoch: number;
}
interface PortalSession {
  expires: number;
  epoch: number;
}
interface Grant {
  userCode: string;
  serverId: string;
  serverName: string;
  deviceName: string;
  expires: number;
  epoch: number;
  state: "pending" | "approved" | "denied";
  lastPoll: number;
}
const COOKIE = "__Host-cs_owner";
const TTL = 300_000;
const encoder = new TextEncoder();
const hex = (bytes: Uint8Array) =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const random = () => hex(crypto.getRandomValues(new Uint8Array(32)));
const digest = async (s: string) =>
  hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(s))));
const json = (data: unknown, status = 200, headers: HeadersInit = {}) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
class Failure extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
function field(data: Record<string, unknown>, key: string, max = 256): string {
  const value = data[key];
  if (typeof value !== "string" || !value.length || value.length > max)
    throw new Failure(400, `Invalid ${key}`);
  return value;
}
function equal(a: string, b: string): boolean {
  const x = encoder.encode(a),
    y = encoder.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}
async function passwordHash(password: string, salt: string): Promise<string> {
  // OWASP's 16 MiB scrypt profile. Serialized in the DO to bound memory and avoid account races.
  return hex(await scryptAsync(password, salt, { N: 2 ** 14, r: 8, p: 5, dkLen: 32 }));
}
function validateCredentials(username: string, password: string) {
  if (!/^[\w.@+-]{1,64}$/.test(username))
    throw new Failure(400, "Use 1–64 letters, numbers, or . @ + - _ for the username.");
  if (password.length < 12 || password.length > 256)
    throw new Failure(400, "Use a password of 12–256 characters.");
}
function cookie(token: string, maxAge = 43200) {
  return `${COOKIE}=${token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`;
}
function secure(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "no-store");
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set(
    "Content-Security-Policy",
    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
  );
  return new Response(response.body, { status: response.status, headers });
}
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(url.hostname))
        throw new Failure(400, "HTTPS required");
      if (request.method === "GET" && !url.pathname.startsWith("/api/")) {
        const portal = ["/", "/activate", "/account"].includes(url.pathname);
        return secure(await env.ASSETS.fetch(portal ? new URL("/", url) : request));
      }
      return secure(await env.AUTH.get(env.AUTH.idFromName("owner")).fetch(request));
    } catch (e) {
      return secure(
        json(
          { error: e instanceof Failure ? e.message : "Authentication service unavailable" },
          e instanceof Failure ? e.status : 503,
        ),
      );
    }
  },
};

export class OwnerAuth {
  constructor(
    private ctx: DurableObjectState,
    private env: Env,
  ) {}
  // Each action, including approval and consumption, completes before the next request.
  async fetch(request: Request): Promise<Response> {
    return this.ctx.blockConcurrencyWhile(async () => {
      try {
        return await this.handle(request);
      } catch (e) {
        return json(
          { error: e instanceof Failure ? e.message : "Authentication service unavailable" },
          e instanceof Failure ? e.status : 503,
        );
      }
    });
  }
  private async account(): Promise<Account> {
    let account = await this.ctx.storage.get<Account>("account");
    if (!account) {
      const { OWNER_USERNAME: username, OWNER_PASSWORD: password } = this.env;
      if (!username || !password) throw new Failure(503, "Owner account has not been configured");
      validateCredentials(username, password);
      const salt = random();
      account = { username, salt, hash: await passwordHash(password, salt), epoch: 1 };
      await this.ctx.storage.put("account", account);
    }
    return account;
  }
  private async limit(key: string, max: number, window = 60_000) {
    const bucket = Math.floor(Date.now() / window);
    const id = `rate:${key}:${bucket}`;
    const old = await this.ctx.storage.get<{ count: number; expires: number }>(id);
    if ((old?.count ?? 0) >= max) throw new Failure(429, "Too many attempts. Try again later.");
    await this.ctx.storage.put(id, {
      count: (old?.count ?? 0) + 1,
      expires: (bucket + 1) * window,
    });
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > Date.now() + 60_000)
      await this.ctx.storage.setAlarm(Date.now() + 60_000);
  }
  private async prune() {
    const next = (await this.ctx.storage.get<number>("nextPrune")) ?? 0;
    if (next > Date.now()) return;
    let nextExpiration = Infinity;
    for (const prefix of ["rate:", "session:", "grant:"]) {
      const items = await this.ctx.storage.list<{ expires: number; userCode?: string }>({ prefix });
      const keys: string[] = [];
      for (const [key, value] of items) {
        if (value.expires <= Date.now()) {
          keys.push(key);
          if (value.userCode) keys.push(`code:${value.userCode}`);
        } else nextExpiration = Math.min(nextExpiration, value.expires);
      }
      if (keys.length) await this.ctx.storage.delete(keys);
    }
    await this.ctx.storage.put("nextPrune", Date.now() + 60_000);
    if (Number.isFinite(nextExpiration))
      await this.ctx.storage.setAlarm(Math.max(Date.now() + 60_000, nextExpiration));
    else await this.ctx.storage.deleteAlarm();
  }
  async alarm() {
    await this.ctx.blockConcurrencyWhile(() => this.prune());
  }
  private async verify(account: Account, username: string, password: string) {
    const hash = await passwordHash(password, account.salt);
    if (!equal(hash, account.hash) || !equal(username, account.username))
      throw new Failure(401, "Incorrect username or password");
  }
  private async session(request: Request, account: Account): Promise<string> {
    const token = request.headers
      .get("cookie")
      ?.split(";")
      .map((s) => s.trim())
      .find((s) => s.startsWith(`${COOKIE}=`))
      ?.slice(COOKIE.length + 1);
    if (!token || !/^[a-f0-9]{64}$/.test(token)) throw new Failure(401, "Sign in to continue");
    const key = `session:${await digest(token)}`;
    const session = await this.ctx.storage.get<PortalSession>(key);
    if (!session || session.expires <= Date.now() || session.epoch !== account.epoch)
      throw new Failure(401, "Sign in to continue");
    return key;
  }
  private async newSession(account: Account): Promise<string> {
    const sessions = await this.ctx.storage.list({ prefix: "session:" });
    if (sessions.size >= 100) await this.ctx.storage.delete(sessions.keys().next().value!);
    const token = random();
    await this.ctx.storage.put(`session:${await digest(token)}`, {
      expires: Date.now() + 43_200_000,
      epoch: account.epoch,
    });
    return cookie(token);
  }
  private async grant(code: string, account: Account): Promise<[string, Grant]> {
    const userCode = code.toUpperCase().replace(/[\s-]/g, "");
    if (!/^[A-HJ-NP-Z2-9]{6}$/.test(userCode))
      throw new Failure(400, "Enter the six-character code shown on your device");
    const key = await this.ctx.storage.get<string>(`code:${userCode}`);
    const grant = key ? await this.ctx.storage.get<Grant>(key) : undefined;
    if (
      !key ||
      !grant ||
      grant.expires <= Date.now() ||
      grant.epoch !== account.epoch ||
      grant.state !== "pending"
    )
      throw new Failure(410, "Code expired or already used. Request a new code on your device.");
    return [key, grant];
  }
  private async handle(request: Request): Promise<Response> {
    const url = new URL(request.url),
      path = url.pathname;
    if (!path.startsWith("/api/")) throw new Failure(404, "Not found");
    const server = path.startsWith("/api/server/");
    if (server) {
      if (this.env.SERVER_SECRET?.length < 32 || !this.env.SERVER_SECRET)
        throw new Failure(503, "Server authentication has not been configured");
      if (!equal(request.headers.get("authorization") ?? "", `Bearer ${this.env.SERVER_SECRET}`))
        throw new Failure(401, "Invalid server credentials");
    } else if (request.method !== "GET") {
      if (
        request.headers.get("origin") !== url.origin ||
        request.headers.get("sec-fetch-site") === "cross-site"
      )
        throw new Failure(403, "Open this page directly to continue");
    }
    if (request.method !== "GET" && request.method !== "POST")
      throw new Failure(405, "Method not allowed");
    let data: Record<string, unknown> = {};
    if (request.method === "POST") {
      if (!request.headers.get("content-type")?.startsWith("application/json"))
        throw new Failure(400, "JSON required");
      const reader = request.body?.getReader();
      let raw = "";
      let size = 0;
      const decoder = new TextDecoder();
      if (reader) {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > 4096) {
            await reader.cancel();
            throw new Failure(400, "Request too large");
          }
          raw += decoder.decode(value, { stream: true });
        }
        raw += decoder.decode();
      }
      try {
        data = JSON.parse(raw);
      } catch {
        throw new Failure(400, "Invalid JSON");
      }
      if (!data || typeof data !== "object" || Array.isArray(data))
        throw new Failure(400, "Invalid JSON object");
    }
    await this.prune();
    const account = await this.account();
    const clientIp = server
      ? typeof data.clientIp === "string"
        ? data.clientIp.slice(0, 128)
        : "server"
      : (request.headers.get("CF-Connecting-IP") ?? "local");
    const ipHash = await digest(clientIp);
    if (path === "/api/login" || path === "/api/server/login") {
      if (request.method !== "POST") throw new Failure(405, "POST required");
      await this.limit(`login:${ipHash}`, 5, 900_000);
      if (server) field(data, "serverId", 128);
      await this.verify(account, field(data, "username", 64), field(data, "password"));
      return server
        ? json({ epoch: account.epoch })
        : json({ username: account.username }, 200, {
            "set-cookie": await this.newSession(account),
          });
    }
    if (server) {
      if (request.method !== "POST") throw new Failure(405, "POST required");
      const serverId = field(data, "serverId", 128);
      if (path === "/api/server/epoch") return json({ epoch: account.epoch });
      if (path === "/api/server/device") {
        await this.limit(`start:${serverId}`, 10);
        await this.limit("start-global", 50);
        if ((await this.ctx.storage.list({ prefix: "grant:" })).size >= 200)
          throw new Failure(429, "Too many pending sign-ins. Try again later.");
        const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        let userCode: string;
        do {
          userCode = Array.from(
            crypto.getRandomValues(new Uint8Array(6)),
            (b) => alphabet[b % alphabet.length],
          ).join("");
        } while (await this.ctx.storage.get(`code:${userCode}`));
        const deviceCode = random(),
          key = `grant:${await digest(deviceCode)}`;
        const grant: Grant = {
          userCode,
          serverId,
          serverName: field(data, "serverName", 100),
          deviceName: field(data, "deviceName", 100),
          expires: Date.now() + TTL,
          epoch: account.epoch,
          state: "pending",
          lastPoll: 0,
        };
        await this.ctx.storage.put({ [key]: grant, [`code:${userCode}`]: key });
        return json({
          userCode,
          deviceCode,
          verificationUri: `${url.origin}/activate`,
          expiresIn: 300,
          interval: 5,
        });
      }
      if (path === "/api/server/poll") {
        await this.limit(`poll:${serverId}`, 1800);
        const deviceCode = field(data, "deviceCode", 64);
        if (!/^[a-f0-9]{64}$/.test(deviceCode)) throw new Failure(400, "Invalid device secret");
        const key = `grant:${await digest(deviceCode)}`,
          grant = await this.ctx.storage.get<Grant>(key);
        if (
          !grant ||
          grant.serverId !== serverId ||
          grant.epoch !== account.epoch ||
          grant.expires <= Date.now()
        )
          throw new Failure(410, "Sign-in expired. Request a new code.");
        if (Date.now() - grant.lastPoll < 4500)
          throw new Failure(429, "Wait five seconds before checking again");
        grant.lastPoll = Date.now();
        if (grant.state !== "pending") {
          await this.ctx.storage.delete([key, `code:${grant.userCode}`]);
          if (grant.state === "denied")
            throw new Failure(410, "Sign-in was declined. Request a new code.");
          return json({ status: "approved", epoch: account.epoch, deviceName: grant.deviceName });
        }
        await this.ctx.storage.put(key, grant);
        return json({ status: "pending" });
      }
      throw new Failure(404, "Not found");
    }
    const sessionKey = await this.session(request, account);
    if (path === "/api/account" && request.method === "GET")
      return json({ username: account.username });
    if (request.method !== "POST") throw new Failure(405, "POST required");
    if (path === "/api/logout") {
      await this.ctx.storage.delete(sessionKey);
      return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
    }
    if (path === "/api/device/lookup" || path === "/api/device/approve") {
      await this.limit(`codes:${sessionKey}`, 5, 300_000);
      await this.limit("codes-global", 20, 300_000);
      const [key, grant] = await this.grant(field(data, "userCode", 16), account);
      if (path === "/api/device/lookup")
        return json({ serverName: grant.serverName, deviceName: grant.deviceName });
      if (typeof data.approve !== "boolean") throw new Failure(400, "Choose approve or decline");
      grant.state = data.approve ? "approved" : "denied";
      await this.ctx.storage.put(key, grant);
      return json({ ok: true });
    }
    if (path === "/api/account" || path === "/api/revoke-all") {
      if (path === "/api/account") {
        await this.limit(`change:${ipHash}`, 5, 900_000);
        await this.verify(account, account.username, field(data, "currentPassword"));
        const username = field(data, "username", 64),
          password = field(data, "password");
        validateCredentials(username, password);
        account.username = username;
        account.salt = random();
        account.hash = await passwordHash(password, account.salt);
      }
      account.epoch++;
      await this.ctx.storage.put("account", account);
      const sessions = await this.ctx.storage.list({ prefix: "session:" });
      if (sessions.size) await this.ctx.storage.delete([...sessions.keys()]);
      return json({ username: account.username }, 200, {
        "set-cookie": await this.newSession(account),
      });
    }
    throw new Failure(404, "Not found");
  }
}
