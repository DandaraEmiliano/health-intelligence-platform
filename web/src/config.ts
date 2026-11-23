/** Runtime configuration from Vite environment variables, with local-development defaults. */
export interface AppConfig {
  /** Empty: same origin. The dev server and nginx forward /api, /bff, /oauth2, /login and /logout to the BFF (ADR 0014). */
  apiBaseUrl: string;
}

export function readConfig(env: Record<string, string | undefined> = import.meta.env): AppConfig {
  const apiBaseUrl = env.VITE_API_BASE_URL ?? "";
  if (apiBaseUrl !== "" && !/^https?:\/\//.test(apiBaseUrl)) {
    throw new Error("Invalid apiBaseUrl: expected an http(s) URL or empty for the same origin");
  }
  return { apiBaseUrl: apiBaseUrl.replace(/\/$/, "") };
}

export const config = readConfig();
