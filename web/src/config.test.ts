import { describe, expect, it } from "vitest";

import { readConfig } from "./config";

describe("readConfig", () => {
  it("defaults to the same origin (the BFF behind the dev server or nginx)", () => {
    expect(readConfig({})).toEqual({ apiBaseUrl: "", authMode: "bff" });
  });

  it("accepts an absolute base and drops a trailing slash", () => {
    expect(readConfig({ VITE_API_BASE_URL: "https://app.example.org/" })).toEqual({ apiBaseUrl: "https://app.example.org", authMode: "bff" });
  });

  it("reads the sign-in mode and rejects unknown ones", () => {
    expect(readConfig({ VITE_AUTH_MODE: "local" }).authMode).toBe("local");
    expect(() => readConfig({ VITE_AUTH_MODE: "keycloak" })).toThrow(/authMode/);
  });

  it("rejects a non-URL API base", () => {
    expect(() => readConfig({ VITE_API_BASE_URL: "javascript:alert(1)" })).toThrow(/apiBaseUrl/);
  });
});
