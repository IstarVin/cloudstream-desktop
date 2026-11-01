import { afterEach, beforeEach, describe, expect, it, vi } from "vite-plus/test";
import { playerSocket, streamLinks, streamSearch } from "./api";

/** EventSource stand-in: tests push server events with `emit`. */
class FakeEventSource {
  static last: FakeEventSource;
  private listeners = new Map<string, ((e: Event) => void)[]>();
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }

  addEventListener(type: string, fn: (e: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }

  /** A named server event with data, or (no data) a connection error like the browser's. */
  emit(type: string, data?: unknown) {
    const e =
      data === undefined ? new Event(type) : new MessageEvent(type, { data: JSON.stringify(data) });
    for (const fn of this.listeners.get(type) ?? []) fn(e);
  }

  close() {
    this.closed = true;
  }
}

beforeEach(() => vi.stubGlobal("EventSource", FakeEventSource));
afterEach(() => vi.unstubAllGlobals());

describe("streamSearch", () => {
  it("delivers results and per-provider errors until done", () => {
    const results = vi.fn();
    const errors = vi.fn();
    const done = vi.fn();
    streamSearch("dune part 2", { results, error: errors, done });
    const es = FakeEventSource.last;
    expect(es.url).toBe("/api/search?q=dune+part+2");

    es.emit("results", { provider: "Dulo", items: [{ name: "Dune" }] });
    es.emit("error", { provider: "Movy", error: "Timed out" });
    expect(results).toHaveBeenCalledWith("Dulo", [{ name: "Dune" }]);
    expect(errors).toHaveBeenCalledWith("Movy", "Timed out");
    expect(es.closed).toBe(false);

    es.emit("done", {});
    expect(done).toHaveBeenCalledOnce();
    expect(es.closed).toBe(true);
  });

  it("finishes instead of reconnecting when the connection drops", () => {
    const done = vi.fn();
    streamSearch("x", { results: vi.fn(), error: vi.fn(), done });
    FakeEventSource.last.emit("error");
    expect(done).toHaveBeenCalledOnce();
    expect(FakeEventSource.last.closed).toBe(true);
  });
});

describe("streamLinks", () => {
  it("delivers links, subtitles and the provider's error", () => {
    const link = vi.fn();
    const subtitle = vi.fn();
    const error = vi.fn();
    const done = vi.fn();
    streamLinks("Dulo", '{"id":1}', { link, subtitle, error, done });
    const es = FakeEventSource.last;
    expect(es.url).toBe("/api/links?provider=Dulo&data=%7B%22id%22%3A1%7D");

    es.emit("link", { id: "l1", source: "Dulo Pickle" });
    es.emit("subtitle", { id: "s1", lang: "English" });
    es.emit("error", { error: "Needs Chromium" });
    es.emit("done", {});
    expect(link).toHaveBeenCalledWith({ id: "l1", source: "Dulo Pickle" });
    expect(subtitle).toHaveBeenCalledWith({ id: "s1", lang: "English" });
    expect(error).toHaveBeenCalledWith("Needs Chromium");
    expect(done).toHaveBeenCalledOnce();
  });
});

describe("playerSocket", () => {
  class FakeWebSocket {
    static all: FakeWebSocket[] = [];
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    constructor(readonly url: string) {
      FakeWebSocket.all.push(this);
    }
    close() {
      this.onclose?.();
    }
  }

  beforeEach(() => {
    FakeWebSocket.all = [];
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.stubGlobal("location", { protocol: "http:", host: "192.168.1.5:6969" });
  });
  afterEach(() => vi.useRealTimers());

  it("parses state, reconnects after a drop, and stays closed once stopped", () => {
    const states = vi.fn();
    const open = vi.fn();
    const stop = playerSocket(states, open);
    const first = FakeWebSocket.all[0];
    expect(first.url).toBe("ws://192.168.1.5:6969/api/player/events");

    first.onopen?.();
    first.onmessage?.({ data: JSON.stringify({ status: "playing", position: 12 }) });
    expect(open).toHaveBeenLastCalledWith(true);
    expect(states).toHaveBeenCalledWith({ status: "playing", position: 12 });

    first.onclose?.();
    expect(open).toHaveBeenLastCalledWith(false);
    vi.advanceTimersByTime(1500);
    expect(FakeWebSocket.all).toHaveLength(2);

    stop();
    vi.advanceTimersByTime(5000);
    expect(FakeWebSocket.all).toHaveLength(2);
  });
});
