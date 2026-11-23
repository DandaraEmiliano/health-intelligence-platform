import type { components } from "./schema";

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
