<script lang="ts">
  import { Cast, Check } from "@lucide/svelte";
  import { cast } from "../cast.svelte";
  import { playTarget } from "../play-target.svelte";
  import { DEVICE_ICONS } from "./device-icons";

  let { class: cls = "" }: { class?: string } = $props();

  const id = $props.id();
  let menu = $state<HTMLElement>();
  let button = $state<HTMLButtonElement>();

  /** Opens beside the button, inside the viewport, since popovers sit in the top layer rather than under their parent. */
  function place(open: boolean) {
    if (!open || !menu || !button) return;
    const r = button.getBoundingClientRect();
    const w = Math.min(320, window.innerWidth - 16);
    const below = r.bottom + 8 + menu.offsetHeight <= window.innerHeight;
    menu.style.width = `${w}px`;
    menu.style.left = `${Math.max(8, Math.min(r.right - w, window.innerWidth - w - 8))}px`;
    menu.style.top = below ? `${r.bottom + 8}px` : "auto";
    menu.style.bottom = below ? "auto" : `${window.innerHeight - r.top + 8}px`;
  }

  function pick(target: string) {
    playTarget.select(target);
    menu?.hidePopover();
  }
</script>

{#if playTarget.options.some((o) => o.id !== "browser" && (!o.disabled || o.busy))}
  <button
    bind:this={button}
    class={["cast-btn", cls, { on: playTarget.remote }]}
    popovertarget="cast-menu-{id}"
    aria-label={`Play on: ${playTarget.label}`}
    title={`Play on: ${playTarget.label}`}
    ><Cast size={22} strokeWidth={1.8} aria-hidden="true" /></button
  >
  <div
    bind:this={menu}
    id="cast-menu-{id}"
    class="cast-menu"
    popover="auto"
    ontoggle={(e) => place(e.newState === "open")}
  >
    <h2>Play on</h2>
    <div role="radiogroup" aria-label="Play on">
      {#each playTarget.options as target (target.id)}
        {@const Icon = DEVICE_ICONS[target.kind]}
        <button
          class={["opt", { on: playTarget.effective === target.id }]}
          role="radio"
          aria-checked={playTarget.effective === target.id}
          disabled={target.disabled}
          title={target.busy
            ? "Another device is playing on this one"
            : target.disabled
              ? "mpv isn't available on the server (headless host or mpv.enabled = false)"
              : undefined}
          onclick={() => pick(target.id)}
        >
          <Icon size={20} strokeWidth={1.8} aria-hidden="true" />
          <span class="name">{target.label}</span>
          {#if target.busy}<span class="busy">In use</span>{/if}
          {#if playTarget.effective === target.id}<Check
              size={18}
              strokeWidth={2}
              aria-hidden="true"
            />{/if}
        </button>
      {/each}
    </div>
    {#if !cast.connected}
      <p class="note">Reconnecting to your other devices…</p>
    {:else if !cast.targets.length}
      <p class="note">Other devices show up here once they're open and allow casting.</p>
    {/if}
  </div>
{/if}

<style>
  .cast-btn {
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: inherit;
    transition:
      background-color 150ms ease,
      color 150ms ease,
      transform 150ms ease;
  }
  .cast-btn:hover {
    background: var(--surface-2);
  }
  .cast-btn:active {
    transform: scale(0.92);
  }
  .cast-btn.on {
    color: var(--accent);
  }
  .cast-menu {
    position: fixed;
    inset: auto;
    margin: 0;
    padding: 8px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-2);
    color: var(--text);
    box-shadow: 0 12px 40px rgb(0 0 0 / 0.5);
    max-height: min(70dvh, 480px);
    overflow-y: auto;
  }
  h2 {
    margin: 0;
    padding: 8px 10px 6px;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .opt {
    width: 100%;
    min-height: 48px;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 10px;
    border-radius: var(--radius-sm);
    text-align: left;
    font-size: 15px;
  }
  .opt:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .opt.on {
    color: var(--accent);
    background: var(--accent-soft);
  }
  .opt:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .busy {
    font-size: 12px;
    color: var(--muted);
  }
  .note {
    margin: 0;
    padding: 8px 10px 6px;
    font-size: 13px;
    color: var(--muted);
  }
</style>
