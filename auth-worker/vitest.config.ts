import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
export default defineConfig({
  test: { testTimeout: 60000, hookTimeout: 30000 },
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          OWNER_USERNAME: "owner",
          OWNER_PASSWORD: "test-owner-password-123",
          SERVER_SECRET: "test-service-secret-at-least-32-characters",
        },
      },
    }),
  ],
});
