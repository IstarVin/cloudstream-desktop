import { describe, expect, it } from "vite-plus/test";
import { extractToken, pairUrl } from "./pair-scan";

const T = "a".repeat(48);

describe("extractToken", () => {
  it("reads the token from a pairing link and ignores its host", () => {
    expect(extractToken(`http://192.168.1.5:8080/pair?token=${T}`)).toBe(T);
    expect(extractToken(`https://other.example/pair?token=${T}&x=1`)).toBe(T);
  });
  it("accepts a bare token or a host-less query", () => {
    expect(extractToken(` ${T}\n`)).toBe(T);
    expect(extractToken(`/pair?token=${T}`)).toBe(T);
  });
  it("rejects anything else", () => {
    expect(extractToken("")).toBeNull();
    expect(extractToken("hello")).toBeNull();
    expect(extractToken("http://x/pair")).toBeNull();
  });
});

describe("pairUrl", () => {
  it("is origin-relative", () => {
    expect(pairUrl(T)).toBe(`/pair?token=${T}`);
  });
});
