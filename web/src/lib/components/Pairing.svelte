<script lang="ts">
  import { Smartphone } from "@lucide/svelte";
</script>

<main class="pair">
  <div class="card">
    <div class="icon"><Smartphone size={32} strokeWidth={1.8} aria-hidden="true" /></div>
    <h1>Pair this device</h1>
    <p>
      On your desktop, open Cloudstream and go to <b>Settings → Pair a phone</b>, then scan the code with this device's
      camera.
    </p>
    <p class="muted">The code signs this browser in for a year. You can sign every device out from the desktop.</p>
  </div>
</main>

<style>
  .pair {
    min-height: 100vh;
    display: grid;
    place-items: center;
    padding: 24px;
  }
  .card {
    max-width: 380px;
    text-align: center;
  }
  .icon {
    width: 72px;
    height: 72px;
    margin: 0 auto 20px;
    border-radius: 20px;
    display: grid;
    place-items: center;
    background: var(--accent-soft);
    color: var(--accent);
  }
  h1 {
    font-size: 24px;
    margin-bottom: 12px;
  }
  p {
    margin: 0 0 12px;
  }
</style>
