/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true, // the Keycloak client only allows redirects to http://localhost:5173
    // Same origin for the browser (ADR 0014): the BFF holds the session and relays the API.
    // Host is kept (no changeOrigin) so the BFF builds redirect URIs for localhost:5173.
    proxy: Object.fromEntries(
      ["/api", "/bff", "/oauth2", "/login", "/logout", "/actuator/health"].map((path) => [
        path,
        { target: "http://localhost:8088", xfwd: true },
      ]),
    ),
  },
  build: {
    sourcemap: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
  },
});
