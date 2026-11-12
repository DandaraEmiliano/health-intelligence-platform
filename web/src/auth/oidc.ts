import { InMemoryWebStorage, WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";

import { config } from "../config";

/**
 * OIDC settings (ADR 0002, ADR 0013).
 *
 * - Authorization Code + PKCE (oidc-client-ts uses PKCE by default for the code flow).
 * - Tokens are kept in memory only, never in localStorage or sessionStorage: a page reload
 *   starts a new sign-in round trip, which is the price of not persisting health-data tokens.
 * - Access tokens live 5 minutes (realm setting) and are renewed with the refresh token.
 */
export const oidcSettings: UserManagerSettings = {
  authority: config.oidcAuthority,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: "code",
  scope: "openid profile email",
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true,
};

export const authProviderProps: AuthProviderProps = {
  ...oidcSettings,
  // Remove ?code=&state= from the address bar after the redirect back from Keycloak.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
