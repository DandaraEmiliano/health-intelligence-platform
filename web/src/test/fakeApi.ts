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
  calls: { method: string; path: string; search: string; body?: unknown; headers: Record<string, string> }[];
  documents: Record<string, unknown>[];
  /** Canned analytics answers: tracked analytes, series by analyte id, timeline items. */
  tracked: unknown[];
  series: Record<string, unknown>;
  timeline: unknown[];
  /** Laboratory reports by id, with a version for the ETag. */
  reports: Record<string, { version: number; data: Record<string, unknown> & { results: Record<string, unknown>[] } }>;
  /** Makes the next PUT/PATCH fail with 412, as if someone else changed the resource. */
  conflictOnNextWrite: boolean;
  /** Assistant conversations by id, with their messages oldest first. */
  conversations: Record<string, { data: Record<string, unknown>; messages: Record<string, unknown>[] }>;
  /** How the next questions are answered: an assistant message, or a problem code with its status. */
  answers: ({ status: number; code: string } | Record<string, unknown>)[];
  /** In-app notifications, newest first. */
  notifications: Record<string, unknown>[];
  /** Canned answers for the summary, the out-of-range list and comparisons ("left|right" key). */
  summary: Record<string, unknown> | null;
  outOfRange: Record<string, unknown>[];
  comparisons: Record<string, Record<string, unknown>>;
}

let sequence = 0;
const now = "2026-11-18T12:00:00Z";

export function fakeState(partial: Partial<FakeApiState> = {}): FakeApiState {
  return { consents: [], patient: null, records: { allergies: [], medications: [], conditions: [] }, documents: [], reports: {}, tracked: [], series: {}, timeline: [], calls: [], conflictOnNextWrite: false, conversations: {}, answers: [], notifications: [], summary: null, outOfRange: [], comparisons: {}, ...partial };
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

function summary(data: Record<string, unknown> & { results: Record<string, unknown>[] }) {
  return {
    id: data.id, documentId: data.documentId, laboratoryName: data.laboratoryName, collectedAt: data.collectedAt,
    status: data.status, resultCount: data.results.length,
    pendingCount: data.results.filter((r) => r.reviewStatus === "PENDING_REVIEW").length,
  };
}

/** A report pending review with two results, like the core returns after an extraction. */
export function pendingReport(id = "rep-1"): FakeApiState["reports"][string] {
  const result = (n: number, name: string, value: string, extra: Record<string, unknown> = {}) => ({
    id: `res-${n}`, reportId: id, sequence: n, rawName: name,
    measurement: { kind: "NUMERIC", rawValue: value, valueNumeric: value.replace(",", ".") },
    unit: "mg/dL", referenceRange: { rawText: "70 a 99", low: "70", high: "99", appliesTo: null },
    flag: null, reviewStatus: "PENDING_REVIEW", warnings: [],
    source: { documentId: "doc-1", page: 1, excerpt: `${name} ${value} mg/dL 70 a 99` }, ...extra,
  });
  return {
    version: 0,
    data: {
      id, documentId: "doc-1", laboratoryName: "Laboratório Sintético Alfa", collectedAt: "2026-11-10", collectedAtRaw: "10/11/2026",
      status: "PENDING_REVIEW", reportWarnings: [], verifiedAt: null,
      results: [
        result(1, "Glicose", "112", { flag: { value: "HIGH", source: "LABORATORY" } }),
        result(2, "Colesterol HDL", "48", { warnings: [{ code: "MULTIPLE_RANGES" }] }),
      ],
    },
  };
}

/** A structured answer citing one verified result, shaped like the core returns it. */
export function assistantAnswer(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    role: "ASSISTANT",
    answer: "Sua glicose de 10/11/2026 foi 112 mg/dL, acima da faixa informada pelo laboratório.",
    facts: [{ resultId: "res-1", reportId: "rep-1", label: "Glicose", value: "112", unit: "mg/dL", referenceRange: "70 a 99", collectedAt: "2026-11-10" }],
    explanations: [{ text: "A glicose é o açúcar no sangue.", sources: [{ type: "GLOSSARY", id: "glucose" }] }],
    uncertainty: "LOW",
    insufficientData: false,
    seekProfessionalCare: { recommended: true, urgency: "ROUTINE" },
    suggestedQuestionsForDoctor: ["Preciso repetir esse exame?"],
    safety: { refused: false, reasonCode: null },
    createdAt: now,
    ...extra,
  };
}

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
    state.calls.push({ method, path, search: url.search, body, headers });

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
    if (path === "/api/v1/health-analytics/analytes") return json(200, { items: state.tracked });
    const series = path.match(/^\/api\/v1\/health-analytics\/analytes\/([^/]+)\/series$/);
    if (series) return state.series[series[1]] ? json(200, state.series[series[1]]) : problem(404, "ANALYTE_NOT_FOUND");
    if (path === "/api/v1/health-timeline") return json(200, { items: state.timeline, nextCursor: null });
    if (path === "/api/v1/laboratory-reports/comparison") {
      const found = state.comparisons[`${url.searchParams.get("left")}|${url.searchParams.get("right")}`];
      return found ? json(200, found) : problem(404, "LABORATORY_REPORT_NOT_FOUND");
    }
    if (path === "/api/v1/laboratory-reports/pending-review/count") {
      return json(200, { reports: Object.values(state.reports).filter((r) => r.data.status === "PENDING_REVIEW").length });
    }
    if (path === "/api/v1/laboratory-reports") {
      return json(200, { items: Object.values(state.reports).map((r) => summary(r.data)), nextCursor: null });
    }
    const lab = path.match(/^\/api\/v1\/laboratory-reports\/([^/]+)(\/review)?$/);
    if (lab) {
      const stored = state.reports[lab[1]];
      if (!stored) return problem(404, "LABORATORY_REPORT_NOT_FOUND");
      if (lab[2] && method === "POST") {
        if (headers["if-match"] !== `"${stored.version}"`) return problem(412, "PRECONDITION_FAILED");
        if (stored.data.status !== "PENDING_REVIEW") return problem(409, "INVALID_STATE");
        for (const decision of body.decisions as { resultId: string; action: string; correction?: Record<string, unknown> }[]) {
          const result = stored.data.results.find((r) => r.id === decision.resultId)!;
          if (decision.action === "CONFIRM") result.reviewStatus = "VERIFIED";
          if (decision.action === "REJECT") result.reviewStatus = "REJECTED";
          if (decision.action === "CORRECT") {
            result.originalExtraction = result.measurement;
            Object.assign(result, { reviewStatus: "CORRECTED", measurement: decision.correction!.measurement,
              unit: decision.correction!.unit, referenceRange: decision.correction!.referenceRange });
          }
        }
        if (body.confirmAllRemaining) stored.data.results.forEach((r) => { if (r.reviewStatus === "PENDING_REVIEW") r.reviewStatus = "VERIFIED"; });
        if (body.finalize) {
          if (stored.data.results.some((r) => r.reviewStatus === "PENDING_REVIEW")) return problem(409, "REVIEW_INCOMPLETE");
          stored.data.status = "VERIFIED";
          stored.data.verifiedAt = now;
        }
        stored.version += 1;
      }
      return json(200, { ...stored.data, ...summary(stored.data) }, stored.version);
    }
    if (path === "/api/v1/notifications") {
      const status = url.searchParams.get("status");
      return json(200, { items: state.notifications.filter((n) => !status || n.status === status), nextCursor: null });
    }
    const notice = path.match(/^\/api\/v1\/notifications\/([^/]+)\/read$/);
    if (notice && method === "POST") {
      const found = state.notifications.find((n) => n.id === notice[1]);
      if (!found) return problem(404, "NOTIFICATION_NOT_FOUND");
      if (found.status === "UNREAD") Object.assign(found, { status: "READ", readAt: now });
      return json(204);
    }
    if (path === "/api/v1/health-analytics/summary") {
      return json(200, state.summary ?? { reportCount: 0, verifiedResultCount: 0, pendingReviewCount: 0, firstCollectedAt: null, lastCollectedAt: null, mostFrequentAnalytes: [], recentOutOfRange: [] });
    }
    if (path === "/api/v1/health-analytics/out-of-range") return json(200, { items: state.outOfRange, nextCursor: null });
    if (path === "/api/v1/ai/conversations") {
      if (method === "POST") {
        const conversation = { id: `conv-${++sequence}`, title: null, createdAt: now, updatedAt: now };
        state.conversations[conversation.id] = { data: conversation, messages: [] };
        return json(201, conversation);
      }
      return json(200, { items: Object.values(state.conversations).map((c) => c.data).reverse(), nextCursor: null });
    }
    const chat = path.match(/^\/api\/v1\/ai\/conversations\/([^/]+)(\/messages)?$/);
    if (chat) {
      const stored = state.conversations[chat[1]];
      if (!stored) return problem(404, "CONVERSATION_NOT_FOUND");
      if (method === "DELETE") {
        delete state.conversations[chat[1]];
        return json(204);
      }
      if (chat[2] && method === "POST") {
        const next = state.answers.shift() ?? assistantAnswer();
        if ("code" in next && typeof next.status === "number") return problem(next.status, String(next.code));
        const answer = { ...next, messageId: `msg-${++sequence}` };
        stored.messages.push({ messageId: `msg-${++sequence}`, role: "USER", content: body.content, createdAt: now }, answer);
        stored.data.title ??= body.content;
        return json(201, answer);
      }
      return json(200, { ...stored.data, messages: stored.messages, nextCursor: null });
    }
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
