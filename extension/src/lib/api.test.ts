import { describe, expect, it } from "vitest";
import { Api, ApiError, SignedOut } from "./api";

function fake(status: number, body: string, seen: Request[] = []): typeof fetch {
  return async (input, init) => {
    seen.push(new Request(input as string, init));
    return new Response(body, { status });
  };
}

describe("Api", () => {
  it("sends the Bearer token and no cookies", async () => {
    const seen: Request[] = [];
    await new Api("https://s", "tok", fake(200, "[]", seen)).providers();
    const [req] = seen as [Request];
    expect(req.url).toBe("https://s/api/providers");
    expect(req.headers.get("Authorization")).toBe("Bearer tok");
    expect(req.credentials).toBe("omit");
  });

  it("posts JSON bodies", async () => {
    const seen: Request[] = [];
    await new Api("https://s", "tok", fake(200, '{"ok":true}', seen)).playTitle({
      provider: "P",
      url: "u",
      device: "mpv",
    });
    const [req] = seen as [Request];
    expect(req.method).toBe("POST");
    expect(req.headers.get("Content-Type")).toBe("application/json");
    expect(await req.json()).toEqual({ provider: "P", url: "u", device: "mpv" });
  });

  it("turns a rejected token into SignedOut", async () => {
    await expect(
      new Api("https://s", "tok", fake(401, '{"error":"Sign in to continue"}')).providers(),
    ).rejects.toBeInstanceOf(SignedOut);
  });

  it("reports the server's error message", async () => {
    const error = await new Api("https://s", "tok", fake(502, '{"error":"No title at this URL"}'))
      .playTitle({ provider: "P", url: "u" })
      .catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe("No title at this URL");
    expect(error.status).toBe(502);
  });

  it("explains a server that isn't Cloudstream or can't be reached", async () => {
    await expect(new Api("https://s", null, fake(200, "<html>")).status()).rejects.toThrow(
      "isn't a Cloudstream server",
    );
    const offline: typeof fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    const error = await new Api("https://s", null, offline).status().catch((e) => e);
    expect(error.status).toBe(0);
    expect(error.message).toBe("Can't reach https://s");
  });
});
