<script lang="ts">
  import { Cast, Globe, MonitorPlay, Smartphone, Tv } from "@lucide/svelte";
  import type { DeviceKind } from "../lib/types";

  let { kind, size = 16 }: { kind: DeviceKind; size?: number } = $props();
  const Icon = $derived({ mpv: MonitorPlay, tv: Tv, phone: Smartphone, web: Globe }[kind] ?? Cast);
</script>

<Icon {size} strokeWidth={1.8} aria-hidden="true" />
