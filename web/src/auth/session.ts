import { useQuery } from "@tanstack/react-query";

import { config } from "../config";

/**
 * Sign-in state from the BFF (ADR 0014). The browser holds only an HttpOnly session cookie; it
 * never sees a token, so there is nothing here to steal or to store.
 */
export interface SessionUser {
  email: string | null;
  name: string | null;
}

export const sessionKey = ["session"] as const;

export async function fetchSession(fetchImpl: typeof fetch = fetch): Promise<SessionUser | null> {
  const response = await fetchImpl(`${config.apiBaseUrl}/bff/user`, { credentials: "same-origin", headers: { Accept: "application/json" } });
  if (response.status === 401) return null;
  if (!response.ok) throw new Error(`session check failed: ${response.status}`);
  return (await response.json()) as SessionUser;
}

export function useSession() {
  return useQuery({ queryKey: sessionKey, queryFn: () => fetchSession(), retry: false, staleTime: 60_000 });
}

/** The CSRF token the BFF puts in a readable cookie; unsafe requests echo it in X-XSRF-TOKEN. */
export function csrfToken(cookies: string = document.cookie): string | undefined {
  const match = cookies.split(";").map((part) => part.trim()).find((part) => part.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : undefined;
}

/** Navigation side effects, in one object so tests can replace them. */
export const browser = {
  go(url: string) {
    window.location.assign(url);
  },
  /**
   * Ends the BFF session with the CSRF token in a header, then goes to the identity provider's
   * logout page (the BFF answers with its URL), which brings the user back to the home page.
   */
  async postLogout() {
    const token = csrfToken();
    const response = await fetch(`${config.apiBaseUrl}/logout`, {
      method: "POST",
      credentials: "same-origin",
      headers: { Accept: "application/json", ...(token ? { "X-XSRF-TOKEN": token } : {}) },
    });
    const body = response.ok ? ((await response.json()) as { redirectUrl?: string }) : {};
    window.location.assign(body.redirectUrl ?? "/");
  },
};

export function signIn(options: { createAccount?: boolean } = {}) {
  browser.go(`${config.apiBaseUrl}/oauth2/authorization/keycloak${options.createAccount ? "?prompt=create" : ""}`);
}

export function signOut() {
  void browser.postLogout();
}
