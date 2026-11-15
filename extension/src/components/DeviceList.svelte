<script lang="ts">
  import { LoaderCircle } from "@lucide/svelte";
  import type { DeviceInfo } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";

  let {
    devices,
    loading,
    busy,
    onpick,
  }: {
    devices: DeviceInfo[] | null;
    loading: boolean;
    /** The device being started, shown with a spinner. */
    busy: string | null;
    onpick: (device: DeviceInfo) => void;
  } = $props();
</script>

{#if devices === null && loading}
  <p class="note"><LoaderCircle class="spin" size={14} aria-hidden="true" /> Finding players…</p>
{:else if !devices?.length}
  <p class="note">No players online. Start mpv on the server or open Cloudstream on a device.</p>
{:else}
  <ul role="list">
    {#each devices as d (d.id)}
      <li>
        <button class="device" disabled={busy !== null} onclick={() => onpick(d)}>
          {#if busy === d.id}
            <LoaderCircle class="spin" size={16} aria-hidden="true" />
          {:else}
            <DeviceIcon kind={d.kind} />
          {/if}
          <span>{d.name}</span>
        </button>
      </li>
    {/each}
  </ul>
{/if}

<style>
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 2px;
  }
  .device {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 8px 10px;
    border: 0;
    border-radius: var(--radius);
    background: transparent;
    color: var(--text);
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .device:hover:not(:disabled),
  .device:focus-visible {
    background: var(--surface-3);
    outline: none;
  }
  .device:disabled {
    cursor: default;
    opacity: 0.7;
  }
  .device span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .note {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    padding: 6px 10px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.4;
  }
  :global(.spin) {
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
