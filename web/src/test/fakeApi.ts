import type { ConsentState } from "../api/resources";

/**
 * In-memory stand-in for core-api, shaped like the contract: ETags from versions, Problem Details
 * on errors, 404 for a missing profile. Tests read `calls` to assert what the UI sent.
 */
interface StoredRecord { id: string; version: number; data: Record<string, unknown> }

export interface FakeApiState {
  consents: ConsentState[];
  patient: { version: number; data: Record<string, unknown> } | null;
  records: Record<string, StoredRecord[]>;
  calls: { method: string; path: string; body?: unknown; headers: Record<string, string> }[];
  documents: Record<string, unknown>[];
  /** Makes the next PUT/PATCH fail with 412, as if someone else changed the resource. */
  conflictOnNextWrite: boolean;
}

let sequence = 0;
const now = "2026-11-18T12:00:00Z";

export function fakeState(partial: Partial<FakeApiState> = {}): FakeApiState {
  return { consents: [], patient: null, records: { allergies: [], medications: [], conditions: [] }, documents: [], calls: [], conflictOnNextWrite: false, ...partial };
}

export function acceptedConsents(ai = true): FakeApiState["consents"] {
  return [
    { type: "TERMS_OF_USE", granted: true, documentVersion: "2026.1", recordedAt: now },
    { type: "PRIVACY_POLICY", granted: true, documentVersion: "2026.1", recordedAt: now },
    { type: "AI_PROCESSING", granted: ai, documentVersion: "ai-2026.1", recordedAt: now },
  ];
}

export function patientRecord(data: Record<string, unknown> = {}): FakeApiState["patient"] {
  return { version: 0, data: { id: "p-1", fullName: "Ana Sintética", birthDate: "1990-04-12", createdAt: now, updatedAt: now, ...data } };
}

const json = (status: number, body?: unknown, etag?: number) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json", ...(etag === undefined ? {} : { ETag: `"${etag}"` }) },
  });
const problem = (status: number, code: string, errors?: unknown[]) =>
  json(status, { type: "about:blank", title: code, status, code, correlationId: "test", ...(errors ? { errors } : {}) });

export function fakeFetch(state: FakeApiState): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const method = init?.method ?? "GET";
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, key) => {
      headers[key] = value;
    });
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    const path = url.pathname;
    state.calls.push({ method, path, body, headers });

    if (path === "/actuator/health/readiness") return json(200, { status: "UP" });
    if (path === "/api/v1/consents") {
      if (method === "POST") {
        const consent = { ...body, recordedAt: now };
        state.consents = [...state.consents.filter((c) => c.type !== body.type), consent];
        return json(201, consent);
      }
      return json(200, { items: state.consents });
    }
    if (path === "/api/v1/patients" && method === "POST") {
      state.patient = { version: 0, data: { id: "p-1", ...body, createdAt: now, updatedAt: now } };
      return json(201, state.patient.data, 0);
    }
    if (path === "/api/v1/patients/me") {
      if (!state.patient) return problem(404, "PATIENT_NOT_FOUND");
      if (method === "PATCH") {
        if (state.conflictOnNextWrite || headers["if-match"] !== `"${state.patient.version}"`) {
          state.conflictOnNextWrite = false;
          state.patient.version += 1;
          return problem(412, "PRECONDITION_FAILED");
        }
        const merged = { ...state.patient.data, ...body };
        for (const [key, value] of Object.entries(body)) if (value === null) delete merged[key];
        state.patient = { version: state.patient.version + 1, data: merged };
      }
      return json(200, state.patient.data, state.patient.version);
    }
    if (path === "/api/v1/accounts/me" && method === "DELETE") return json(202);
    if (path === "/api/v1/documents") return json(200, { items: state.documents, nextCursor: null });
    const doc = path.match(/^\/api\/v1\/documents\/([^/]+)(\/content)?$/);
    if (doc) {
      const found = state.documents.find((d) => d.id === doc[1]);
      if (!found) return problem(404, "DOCUMENT_NOT_FOUND");
      if (doc[2]) return json(200, { url: `http://s3.test/documents/${doc[1]}`, expiresAt: now });
      if (method === "DELETE") {
        state.documents = state.documents.filter((d) => d.id !== doc[1]);
        return json(204);
      }
      return json(200, found);
    }
    const match = path.match(/^\/api\/v1\/patients\/me\/(allergies|medications|conditions)(?:\/([^/]+))?$/);
    if (match) {
      if (!state.patient) return problem(404, "PATIENT_NOT_FOUND");
      const [, kind, id] = match;
      const list = state.records[kind];
      if (!id) {
        if (method === "POST") {
          const record = { id: `r-${++sequence}`, version: 0, data: { ...body, createdAt: now, updatedAt: now } };
          list.push(record);
          return json(201, { id: record.id, ...record.data }, 0);
        }
        return json(200, { items: list.map((r) => ({ id: r.id, ...r.data })) });
      }
      const record = list.find((r) => r.id === id);
      if (!record) return problem(404, "NOT_FOUND");
      if (method === "DELETE") {
        state.records[kind] = list.filter((r) => r.id !== id);
        return json(204);
      }
      if (method === "PUT") {
        if (state.conflictOnNextWrite || headers["if-match"] !== `"${record.version}"`) {
          state.conflictOnNextWrite = false;
          record.version += 1;
          return problem(412, "PRECONDITION_FAILED");
        }
        record.version += 1;
        record.data = { ...body, createdAt: now, updatedAt: now };
      }
      return json(200, { id: record.id, ...record.data }, record.version);
    }
    return problem(404, "NOT_FOUND");
  }) as typeof fetch;
}

/** Stand-in for XMLHttpRequest uploads: records the request and answers with `respond`. */
export class FakeXhr {
  static sent: FakeXhr[] = [];
  static respond: (xhr: FakeXhr) => { status: number; body: unknown } = () => ({ status: 202, body: {} });

  method = "";
  url = "";
  headers: Record<string, string> = {};
  body: FormData | undefined;
  status = 0;
  responseText = "";
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name.toLowerCase()] = value;
  }

  send(body: FormData) {
    this.body = body;
    FakeXhr.sent.push(this);
    queueMicrotask(() => {
      this.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 2 });
      const answer = FakeXhr.respond(this);
      this.status = answer.status;
      this.responseText = JSON.stringify(answer.body);
      this.onload?.();
    });
  }
}
