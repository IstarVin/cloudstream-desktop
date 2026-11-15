import { mount, unmount } from "svelte";
import { defineContentScript } from "wxt/utils/define-content-script";
import { createShadowRootUi } from "wxt/utils/content-script-ui/shadow-root";
import { send } from "../../lib/messages";
import "../../lib/tokens.css";
import Pill from "./Pill.svelte";

export default defineContentScript({
  matches: ["<all_urls>"],
  runAt: "document_idle",
  cssInjectionMode: "ui",
  async main(ctx) {
    if (window.top !== window) return; // not in frames: one button per tab
    const match = await send("match", { url: location.href }).catch(() => null);
    if (!match?.signedIn || !match.provider || match.hidden) return;

    const ui = await createShadowRootUi(ctx, {
      name: "cloudstream-pill",
      position: "inline",
      anchor: "body",
      append: "last",
      isolateEvents: true,
      onMount: (container) =>
        mount(Pill, {
          target: container,
          props: { provider: match.provider!, onclose: () => ui.remove() },
        }),
      onRemove: (app) => app && unmount(app),
    });
    ui.mount();
  },
});
