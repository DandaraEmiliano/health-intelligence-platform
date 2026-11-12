import { createApiClient } from "./client";
import { tokenStore } from "./tokenStore";

/** Application-wide API client. Reads the in-memory token at request time. */
export const api = createApiClient(tokenStore.get);
