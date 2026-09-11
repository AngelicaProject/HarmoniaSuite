import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.test.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary", "lcov", "html"],
      reportsDirectory: "./coverage",
      exclude: [
        "src/**/*.test.ts",
        "src/main.ts",
        "dist/**",
        "coverage/**",
        "**/*.config.*",
        "src/api/types.ts",
        "src/api/requestTypes.ts",
      ],
    },
  },
});
