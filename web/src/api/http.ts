import { config } from "../config";
import { ApiProblemError, isProblem } from "./client";
import { tokenStore } from "./tokenStore";

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
}

/**
 * Calls core-api with the in-memory access token. Non-2xx answers become {@link ApiProblemError}
 * so screens can show the problem code; nothing is retried here.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}, fetchImpl: typeof fetch = fetch): Promise<ApiResult<T>> {
  const headers = new Headers({ Accept: "application/json, application/problem+json" });
  const token = tokenStore.get();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body !== undefined) headers.set("Content-Type", options.contentType ?? "application/json");
  if (options.ifMatch) headers.set("If-Match", options.ifMatch);

  const response = await fetchImpl(`${config.apiBaseUrl}${path}`, {
    method: options.method ?? "GET",
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
