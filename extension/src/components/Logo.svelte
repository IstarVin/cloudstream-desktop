<script lang="ts">
  // The cloud-and-play mark from web/static/favicon.svg, cropped to the cloud.
  let { size = 20 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="14 22 80 60" aria-hidden="true">
  <path
    fill="currentColor"
    fill-rule="evenodd"
    d="M34 79C23 79 16 71 16 61C16 51 23 43 33 42C36 31 45 25 56 27C66 28 73 35 75 45C85 45 92 52 92 62C92 72 84 79 74 79ZM47 45C45.7 44.2 44 45.2 44 46.7V66.3C44 67.8 45.7 68.8 47 68L63.5 58.2C64.8 57.4 64.8 55.6 63.5 54.8Z"
  />
</svg>
