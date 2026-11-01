<script lang="ts" module>
  // Stroke icons (24×24, lucide-style paths) kept inline so the UI works offline on the LAN.
  const paths = {
    home: "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z",
    search: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-4.35-4.35",
    library: "M4 4h4v16H4zM10 4h4v16h-4zM16.5 4.5l3.8 1 -4 15.5-3.8-1z",
    settings:
      "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    play: "M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z",
    pause: "M7 4h3v16H7zM14 4h3v16h-3z",
    next: "M5 4.5v15l10-7.5zM19 5v14",
    prev: "M19 4.5v15L9 12zM5 5v14",
    back10: "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M9.5 10v5M12.5 10h2.5v5h-2.5z",
    fwd10: "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5M9.5 10v5M12.5 10h2.5v5h-2.5z",
    stop: "M6 6h12v12H6z",
    close: "M6 6l12 12M18 6 6 18",
    back: "M15 18l-6-6 6-6",
    down: "M6 9l6 6 6-6",
    check: "M5 12.5l4.5 4.5L19 7",
    bookmark: "M6 3h12v18l-6-4-6 4z",
    subtitles: "M3 5h18v14H3zM7 13h4M13 13h4M7 16h10",
    audio: "M11 5 6 9H3v6h3l5 4zM15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13",
    layers: "M12 3 2 8l10 5 10-5zM2 13l10 5 10-5M2 17.5l10 5 10-5",
    refresh: "M21 12a9 9 0 0 1-15.5 6.2L3 16M3 12a9 9 0 0 1 15.5-6.2L21 8M21 3v5h-5M3 21v-5h5",
    star: "M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z",
    tv: "M3 6h18v12H3zM8 21h8M12 18v3",
    alert: "M12 3 2 20h20zM12 10v4M12 17v.5",
    phone: "M7 2h10v20H7zM11 18h2",
    volume: "M11 5 6 9H3v6h3l5 4zM16 9a4 4 0 0 1 0 6",
    eye: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
  } as const;
  export type IconName = keyof typeof paths;
</script>

<script lang="ts">
  let { name, size = 22, fill = false }: { name: IconName; size?: number; fill?: boolean } = $props();
</script>

<svg
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill={fill ? "currentColor" : "none"}
  stroke="currentColor"
  stroke-width="2"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
>
  <path d={paths[name]} />
</svg>
