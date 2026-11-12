import { describe, expect, it } from "vitest";

import { readConfig } from "./config";

describe("readConfig", () => {
  it("defaults to the local development environment", () => {
    expect(readConfig({})).toEqual({
      oidcAuthority: "http://localhost:8180/realms/hip",
      oidcClientId: "hip-web",
      apiBaseUrl: "http://localhost:8080",
    });
  });

  it("rejects a non-URL API base", () => {
    expect(() => readConfig({ VITE_API_BASE_URL: "javascript:alert(1)" })).toThrow(/apiBaseUrl/);
  });
});
