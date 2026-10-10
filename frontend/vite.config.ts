import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the browser talks only to Vite (one origin, no CORS); /api is forwarded to the api service.
// In deployment Caddy plays this role (ADR 0028).
export default defineConfig({
  plugins: [react()],
  worker: { format: "es" }, // MapLibre 6's worker is an ES module
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
