import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the browser talks only to Vite (one origin, no CORS); /api is forwarded to the api service and
// /live (the SSE stream) to live-hub. In deployment Caddy plays this role. PLACEMAP_API_URL and PLACEMAP_LIVE_URL point
// them elsewhere, such as the load-test stack (http://127.0.0.1:58080 and :58081).
export default defineConfig({
  plugins: [react()],
  worker: { format: "es" }, // MapLibre 6's worker is an ES module
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": process.env.PLACEMAP_API_URL ?? "http://localhost:8080",
      "/live": process.env.PLACEMAP_LIVE_URL ?? "http://localhost:8081",
    },
  },
});
