import { runInDurableObject } from "cloudflare:test";
import { env, exports } from "cloudflare:workers";
const SELF = exports.default;
import { describe, it, expect, beforeEach } from "vitest";
const origin = "https://auth.example";
const password = "test-owner-password-123";
const secret = "test-service-secret-at-least-32-characters";
async function portal(path: string, data?: object, cookie = "", source = origin) {
  return SELF.fetch(origin + "/api/" + path, {
    method: data ? "POST" : "GET",
    headers: {
      "content-type": "application/json",
      origin: source,
      cookie,
      "CF-Connecting-IP": "192.0.2.1",
    },
    body: data ? JSON.stringify(data) : undefined,
  });
}
async function server(path: string, data: object = {}, serverId = "server-a") {
  return SELF.fetch(origin + "/api/server/" + path, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Bearer " + secret },
    body: JSON.stringify({ serverId, ...data }),
  });
}
async function login() {
  const res = await portal("login", { username: "owner", password });
  expect(res.status).toBe(200);
  return res.headers.get("set-cookie")!.split(";")[0];
}
async function start() {
  const res = await server("device", { serverName: "Home server", deviceName: "Living room TV" });
  expect(res.status).toBe(200);
  return (await res.json()) as { userCode: string; deviceCode: string };
}
async function modifyGrant(deviceCode: string, change: (value: any) => void) {
  const hash = Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(deviceCode))),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
  const namespace = (env as any).AUTH;
  await runInDurableObject(
    namespace.get(namespace.idFromName("owner")),
    async (_instance, state) => {
      const key = "grant:" + hash,
        value = await state.storage.get(key);
      change(value);
      await state.storage.put(key, value);
    },
  );
}
describe("owner authentication", () => {
  beforeEach(async () => {
    const namespace = (env as any).AUTH;
    await runInDurableObject(
      namespace.get(namespace.idFromName("owner")),
      async (_instance, state) => {
        await state.storage.deleteAll();
        await state.storage.deleteAlarm();
      },
    );
  });
  it("requires service credentials and same-origin browser mutations", async () => {
    expect((await SELF.fetch(origin + "/api/server/epoch", { method: "POST" })).status).toBe(401);
    expect(
      (await portal("login", { username: "owner", password }, "", "https://evil.example")).status,
    ).toBe(403);
    expect((await portal("device/approve", { userCode: "ABC234", approve: true })).status).toBe(
      401,
    );
  });
  it("approves only the matching device secret and consumes a grant once", async () => {
    const cookie = await login(),
      grant = await start();
    expect(grant.userCode).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
    expect(grant.deviceCode).toHaveLength(64);
    expect((await server("poll", { deviceCode: grant.deviceCode }, "server-b")).status).toBe(410);
    expect((await server("poll", { deviceCode: grant.userCode })).status).toBe(400);
    expect((await portal("device/lookup", { userCode: grant.userCode }, cookie)).status).toBe(200);
    expect(
      (await portal("device/approve", { userCode: grant.userCode, approve: true }, cookie)).status,
    ).toBe(200);
    const response = await server("poll", { deviceCode: grant.deviceCode });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      status: "approved",
      deviceName: "Living room TV",
      epoch: 1,
    });
    expect((await server("poll", { deviceCode: grant.deviceCode })).status).toBe(410);
  });
  it("limits polling and expires device grants", async () => {
    const grant = await start();
    expect(await (await server("poll", { deviceCode: grant.deviceCode })).json()).toMatchObject({
      status: "pending",
    });
    expect((await server("poll", { deviceCode: grant.deviceCode })).status).toBe(429);
    await modifyGrant(grant.deviceCode, (value) => (value.expires = Date.now() - 1));
    expect((await server("poll", { deviceCode: grant.deviceCode })).status).toBe(410);
  });
  it("declines rather than authenticating a rejected device", async () => {
    const cookie = await login(),
      grant = await start();
    expect(
      (await portal("device/approve", { userCode: grant.userCode, approve: false }, cookie)).status,
    ).toBe(200);
    expect((await server("poll", { deviceCode: grant.deviceCode })).status).toBe(410);
  });
  it("changes the owner credentials and invalidates old sessions and pending codes", async () => {
    const cookie = await login(),
      grant = await start();
    const changed = await portal(
      "account",
      { username: "new-owner", password: "new-owner-password-123", currentPassword: password },
      cookie,
    );
    expect(changed.status).toBe(200);
    expect((await portal("account", undefined, cookie)).status).toBe(401);
    expect((await server("poll", { deviceCode: grant.deviceCode })).status).toBe(410);
    expect((await server("login", { username: "owner", password })).status).toBe(401);
    const response = await server("login", {
      username: "new-owner",
      password: "new-owner-password-123",
    });
    expect(await response.json()).toMatchObject({ epoch: 2 });
  });
  it("rate limits incorrect passwords", async () => {
    for (let i = 0; i < 5; i++)
      expect((await portal("login", { username: "owner", password: "wrong" })).status).toBe(401);
    expect((await portal("login", { username: "owner", password: "wrong" })).status).toBe(429);
  });
  it("does not keep waking an idle owner object without expiring records", async () => {
    expect((await portal("account")).status).toBe(401);
    const namespace = (env as any).AUTH;
    const alarm = await runInDurableObject(
      namespace.get(namespace.idFromName("owner")),
      async (_instance, state) => state.storage.getAlarm(),
    );
    expect(alarm).toBeNull();
  });
  it("serves the portal with restrictive browser headers", async () => {
    const res = await SELF.fetch(origin + "/activate");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    expect(res.headers.get("cache-control")).toBe("no-store");
  });
});
