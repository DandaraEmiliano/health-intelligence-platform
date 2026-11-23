import { afterEach, describe, expect, it, vi } from "vitest";

import { csrfToken } from "../auth/session";
import { isProblem } from "./client";
import { apiRequest } from "./http";

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    void input;
    void init;
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" },
    });
  });
}

describe("API transport through the BFF (ADR 0014)", () => {
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("sends the session cookie and never an Authorization header", async () => {
    const fetchMock = fakeFetch(200, { id: "p1" });

    await apiRequest("/api/v1/patients/me", {}, fetchMock as unknown as typeof fetch);

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/api/v1/patients/me");
    expect(init?.credentials).toBe("same-origin");
    expect(new Headers(init?.headers).has("Authorization")).toBe(false);
    expect(new Headers(init?.headers).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("echoes the CSRF cookie on unsafe methods", async () => {
    document.cookie = "XSRF-TOKEN=token%2Bvalue; path=/";
    const fetchMock = fakeFetch(201, {});

    await apiRequest("/api/v1/patients", { method: "POST", body: { fullName: "Ana" } }, fetchMock as unknown as typeof fetch);

    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get("X-XSRF-TOKEN")).toBe("token+value");
  });

  it("reads the CSRF token from the cookie string", () => {
    expect(csrfToken("a=1; XSRF-TOKEN=abc; b=2")).toBe("abc");
    expect(csrfToken("a=1")).toBeUndefined();
  });

  it("exposes RFC 9457 problem details as the error", async () => {
    const problem = { type: "about:blank", title: "Not found", status: 404, code: "NOT_FOUND", correlationId: "c-1" };

    await expect(apiRequest("/api/v1/patients/me", {}, fakeFetch(404, problem) as unknown as typeof fetch))
      .rejects.toMatchObject({ problem: { code: "NOT_FOUND" } });
  });
});

describe("isProblem", () => {
  it("rejects bodies without status and code", () => {
    expect(isProblem({ message: "x" })).toBe(false);
    expect(isProblem(null)).toBe(false);
  });
});
