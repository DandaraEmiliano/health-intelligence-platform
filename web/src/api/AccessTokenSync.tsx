import { useEffect } from "react";
import { useAuth } from "react-oidc-context";

import { tokenStore } from "./tokenStore";

/** Mirrors the OIDC access token into the in-memory token store used by the API client. */
export function AccessTokenSync() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  useEffect(() => {
    tokenStore.set(token);
    return () => tokenStore.set(undefined);
  }, [token]);
  return null;
}
