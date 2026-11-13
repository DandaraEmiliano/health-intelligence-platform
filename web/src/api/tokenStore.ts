/**
 * Holds the current access token in memory for the API client (ADR 0013).
 * Updated by <AccessTokenSync/> whenever react-oidc-context renews or clears the user.
 */
let currentToken: string | undefined;

export const tokenStore = {
  get: (): string | undefined => currentToken,
  set: (token: string | undefined): void => {
    currentToken = token;
  },
};
