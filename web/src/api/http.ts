import { config } from "../config";
import { ApiProblemError, isProblem } from "./client";
import { csrfToken } from "../auth/session";

export interface ApiResult<T> {
  data: T;
  /** Strong ETag of the returned resource, when the API sends one (ADR 0009). */
  etag: string | undefined;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  contentType?: string;
  ifMatch?: string;
  /** Same key on a retry, so the API replays the first answer instead of doing it twice (ADR 0009). */
  idempotencyKey?: string;
}

/**
 * Calls core-api through the BFF with the session cookie (ADR 0014); unsafe methods carry the
 * CSRF token. Non-2xx answers become {@link ApiProblemError}
 * so screens can show the problem code; nothing is retried here.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}, fetchImpl: typeof fetch = fetch): Promise<ApiResult<T>> {
  const headers = new Headers({ Accept: "application/json, application/problem+json" });
  const method = options.method ?? "GET";
  if (method !== "GET") {
    const token = csrfToken();
    if (token) headers.set("X-XSRF-TOKEN", token);
  }
  if (options.body !== undefined) headers.set("Content-Type", options.contentType ?? "application/json");
  if (options.ifMatch) headers.set("If-Match", options.ifMatch);
  if (options.idempotencyKey) headers.set("Idempotency-Key", options.idempotencyKey);

  const response = await fetchImpl(`${config.apiBaseUrl}${path}`, {
    method,
    credentials: "same-origin",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  const body: unknown = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    if (isProblem(body)) throw new ApiProblemError(body);
    throw new ApiProblemError({
      type: "about:blank",
      title: response.statusText || "Error",
      status: response.status,
      code: "HTTP_ERROR",
      correlationId: "",
    });
  }
  return { data: body as T, etag: response.headers.get("ETag") ?? undefined };
}

export function problemCode(error: unknown): string | undefined {
  return error instanceof ApiProblemError ? error.problem.code : undefined;
}

export function problemStatus(error: unknown): number | undefined {
  return error instanceof ApiProblemError ? error.problem.status : undefined;
}
