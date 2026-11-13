import { describe, expect, it, vi } from "vitest";

import { createApiClient, isProblem } from "./client";

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async (request: Request) => {
    void request;
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  });
}

describe("api client", () => {
  it("sends the current access token as a bearer token", async () => {
    const fetchMock = fakeFetch(200, { id: "p1" });
    const client = createApiClient(() => "synthetic-token", "http://api.test", fetchMock as unknown as typeof fetch);

    await client.GET("/api/v1/patients/me");

    const request = fetchMock.mock.calls[0][0];
    expect(request.headers.get("Authorization")).toBe("Bearer synthetic-token");
    expect(request.url).toBe("http://api.test/api/v1/patients/me");
  });

  it("sends no Authorization header when signed out", async () => {
    const fetchMock = fakeFetch(401, {});
    const client = createApiClient(() => undefined, "http://api.test", fetchMock as unknown as typeof fetch);

    await client.GET("/api/v1/patients/me");

    expect(fetchMock.mock.calls[0][0].headers.has("Authorization")).toBe(false);
  });

  it("exposes RFC 9457 problem details as the error", async () => {
    const problem = {
      type: "https://docs.health-intelligence-platform.dev/problems/not-found",
      title: "Not found",
      status: 404,
      code: "NOT_FOUND",
      correlationId: "c-1",
    };
    const client = createApiClient(() => "t", "http://api.test", fakeFetch(404, problem) as unknown as typeof fetch);

    const { error, response } = await client.GET("/api/v1/patients/me");

    expect(response.status).toBe(404);
    expect(isProblem(error)).toBe(true);
    expect(error).toMatchObject({ code: "NOT_FOUND" });
  });
});

describe("isProblem", () => {
  it("rejects bodies without status and code", () => {
    expect(isProblem({ message: "x" })).toBe(false);
    expect(isProblem(null)).toBe(false);
  });
});

describe("tokenStore", async () => {
  const { tokenStore } = await import("./tokenStore");
  const { api } = await import("./api");

  it("is read at request time by the application client", async () => {
    const fetchMock = vi.fn(async (request: Request) => {
      void request;
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);

    tokenStore.set("renewed-token");
    await api.GET("/api/v1/patients/me", { fetch: fetchMock as unknown as typeof fetch });
    tokenStore.set(undefined);

    expect(fetchMock.mock.calls[0][0].headers.get("Authorization")).toBe("Bearer renewed-token");
  });
});
