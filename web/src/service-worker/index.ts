import { self } from "$app/service-worker";
import { version } from "$app/env";

const prefix = "cloudstream-offline-";
const cacheName = `${prefix}${version}`;
const offlinePage = "/offline.html";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(cacheName).then((cache) => cache.add(offlinePage)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (name.startsWith(prefix) && name !== cacheName) await caches.delete(name);
      }
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  // Only handle app navigation. API, pairing, media and range requests stay on the network.
  if (
    request.method !== "GET" ||
    request.mode !== "navigate" ||
    url.origin !== self.location.origin ||
    /^\/(api|proxy|pair)(\/|$)/.test(url.pathname)
  )
    return;

  event.respondWith(
    fetch(request).catch(async () => {
      const cache = await caches.open(cacheName);
      return (await cache.match(offlinePage)) ?? Response.error();
    }),
  );
});
