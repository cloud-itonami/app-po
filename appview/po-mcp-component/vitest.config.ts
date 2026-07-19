import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
    server: {
      deps: {
        inline: ["@etzhayyim/sdk-mock", "@noble/hashes"]
      }
    }
  }
});
