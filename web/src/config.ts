/** Runtime configuration from Vite environment variables, with local-development defaults. */
export interface AppConfig {
  /** Empty: same origin. The dev server and nginx forward /api, /bff, /oauth2, /login and /logout to the BFF (ADR 0014). */
  apiBaseUrl: string;
  /**
   * How people sign in: "bff" (Keycloak through the BFF, the default) or "local" (the built-in
   * e-mail and password sign-in of the Render profile, served by core-api itself).
   */
  authMode: "bff" | "local";
}

export function readConfig(env: Record<string, string | undefined> = import.meta.env): AppConfig {
  const apiBaseUrl = env.VITE_API_BASE_URL ?? "";
  if (apiBaseUrl !== "" && !/^https?:\/\//.test(apiBaseUrl)) {
    throw new Error("Invalid apiBaseUrl: expected an http(s) URL or empty for the same origin");
  }
  const authMode = env.VITE_AUTH_MODE ?? "bff";
  if (authMode !== "bff" && authMode !== "local") {
    throw new Error("Invalid authMode: expected bff or local");
  }
  return { apiBaseUrl: apiBaseUrl.replace(/\/$/, ""), authMode };
}

export const config = readConfig();
