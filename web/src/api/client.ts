import createClient, { type Middleware } from "openapi-fetch";

import { config } from "../config";
import type { components, paths } from "./schema";

export type Problem = components["schemas"]["Problem"];

/** Error carrying an RFC 9457 Problem Details body (ADR 0009). */
export class ApiProblemError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(`${problem.status} ${problem.code}: ${problem.title}`);
    this.name = "ApiProblemError";
    this.problem = problem;
  }
}

export function isProblem(body: unknown): body is Problem {
  if (typeof body !== "object" || body === null) return false;
  const candidate = body as Record<string, unknown>;
  return typeof candidate.status === "number" && typeof candidate.code === "string";
}

/** Adds the current access token to every request. The token getter never touches storage. */
export function bearerTokenMiddleware(getToken: () => string | undefined): Middleware {
  return {
    onRequest({ request }) {
      const token = getToken();
      if (token) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      return request;
    },
  };
}

export function createApiClient(getToken: () => string | undefined, baseUrl = config.apiBaseUrl, fetchImpl?: typeof fetch) {
  const client = createClient<paths>({ baseUrl, fetch: fetchImpl });
  client.use(bearerTokenMiddleware(getToken));
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
