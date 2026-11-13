import { config } from "../config";

export type Readiness = "UP" | "DOWN";

/** Public readiness probe of core-api; never throws. */
export async function fetchReadiness(fetchImpl: typeof fetch = fetch): Promise<Readiness> {
  try {
    const response = await fetchImpl(`${config.apiBaseUrl}/actuator/health/readiness`);
    if (!response.ok) return "DOWN";
    const body = (await response.json()) as { status?: string };
    return body.status === "UP" ? "UP" : "DOWN";
  } catch {
    return "DOWN";
  }
}
