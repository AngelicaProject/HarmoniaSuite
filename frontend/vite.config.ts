import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      // Browser-only development fallback. Electron uses harmonia://app and
      // its main-process proxy, so this is never a production gateway contract.
      "/api": process.env.HARMONIA_DEV_GATEWAY_URL || "http://127.0.0.1:8765",
    },
  },
  preview: {
    host: "127.0.0.1",
    port: 4173,
  },
});
