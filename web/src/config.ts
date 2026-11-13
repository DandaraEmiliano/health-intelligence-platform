/** Runtime configuration from Vite environment variables, with local-development defaults. */
export interface AppConfig {
  oidcAuthority: string;
  oidcClientId: string;
  apiBaseUrl: string;
}

export function readConfig(env: Record<string, string | undefined> = import.meta.env): AppConfig {
  const config = {
    oidcAuthority: env.VITE_OIDC_AUTHORITY ?? "http://localhost:8180/realms/hip",
    oidcClientId: env.VITE_OIDC_CLIENT_ID ?? "hip-web",
    apiBaseUrl: env.VITE_API_BASE_URL ?? "http://localhost:8080",
  };
  for (const [key, value] of Object.entries(config)) {
    if (!/^https?:\/\//.test(value) && key !== "oidcClientId") {
      throw new Error(`Invalid ${key}: expected an http(s) URL`);
    }
  }
  return config;
}

export const config = readConfig();
