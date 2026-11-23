import { apiRequest, type ApiResult } from "./http";
import type { components } from "./schema";

type Schemas = components["schemas"];
export type Patient = Schemas["Patient"];
export type PatientCreate = Schemas["PatientCreateRequest"];
export type PatientUpdate = Schemas["PatientUpdateRequest"];
export type BiologicalSex = Schemas["BiologicalSex"];
export type ConsentType = Schemas["ConsentType"];
export type ConsentState = Schemas["ConsentState"];
export type Allergy = Schemas["Allergy"];
export type AllergyRequest = Schemas["AllergyRequest"];
export type Medication = Schemas["Medication"];
export type MedicationRequest = Schemas["MedicationRequest"];
export type MedicalCondition = Schemas["MedicalCondition"];
export type MedicalConditionRequest = Schemas["MedicalConditionRequest"];

/** Versions of the documents the user agrees to. Bumping one asks everybody again. */
export const CONSENT_VERSIONS: Record<ConsentType, string> = {
  TERMS_OF_USE: "2026.1",
  PRIVACY_POLICY: "2026.1",
  AI_PROCESSING: "ai-2026.1",
};

export const patientApi = {
  get: () => apiRequest<Patient>("/api/v1/patients/me"),
  create: (body: PatientCreate) => apiRequest<Patient>("/api/v1/patients", { method: "POST", body }),
  update: (body: PatientUpdate, etag: string) =>
    apiRequest<Patient>("/api/v1/patients/me", {
      method: "PATCH",
      body,
      contentType: "application/merge-patch+json",
      ifMatch: etag,
    }),
};

export const consentApi = {
  list: () => apiRequest<{ items: ConsentState[] }>("/api/v1/consents").then((r) => r.data.items),
  record: (type: ConsentType, granted: boolean) =>
    apiRequest<ConsentState>("/api/v1/consents", {
      method: "POST",
      body: { type, granted, documentVersion: CONSENT_VERSIONS[type] },
    }).then((r) => r.data),
};

export type HealthRecordKind = "allergies" | "medications" | "conditions";

export interface HealthRecordTypes {
  allergies: { record: Allergy; request: AllergyRequest };
  medications: { record: Medication; request: MedicationRequest };
  conditions: { record: MedicalCondition; request: MedicalConditionRequest };
}

export type RecordOf<K extends HealthRecordKind> = HealthRecordTypes[K]["record"];
export type RequestOf<K extends HealthRecordKind> = HealthRecordTypes[K]["request"];

const base = (kind: HealthRecordKind) => `/api/v1/patients/me/${kind}`;

export const healthRecordApi = {
  list: <K extends HealthRecordKind>(kind: K) =>
    apiRequest<{ items: RecordOf<K>[] }>(base(kind)).then((r) => r.data.items),
  get: <K extends HealthRecordKind>(kind: K, id: string): Promise<ApiResult<RecordOf<K>>> =>
    apiRequest<RecordOf<K>>(`${base(kind)}/${id}`),
  create: <K extends HealthRecordKind>(kind: K, body: RequestOf<K>) =>
    apiRequest<RecordOf<K>>(base(kind), { method: "POST", body }),
  replace: <K extends HealthRecordKind>(kind: K, id: string, body: RequestOf<K>, etag: string) =>
    apiRequest<RecordOf<K>>(`${base(kind)}/${id}`, { method: "PUT", body, ifMatch: etag }),
  remove: (kind: HealthRecordKind, id: string) => apiRequest<void>(`${base(kind)}/${id}`, { method: "DELETE" }),
};

export type DocumentStatus = Schemas["DocumentStatus"];
export type HealthDocument = Schemas["Document"];
export interface DocumentPage {
  items: HealthDocument[];
  nextCursor: string | null;
}

export const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;

export const documentApi = {
  list: (cursor?: string) =>
    apiRequest<DocumentPage>(`/api/v1/documents?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`).then((r) => r.data),
  remove: (id: string) => apiRequest<void>(`/api/v1/documents/${id}`, { method: "DELETE" }),
  /** Short-lived link to open the PDF; the API returns it as JSON for browser apps. */
  contentLink: (id: string) =>
    apiRequest<{ url: string; expiresAt: string }>(`/api/v1/documents/${id}/content`).then((r) => r.data),
};

export const accountApi = {
  /** 202: access is blocked now; everything is purged by a scheduled job. */
  requestDeletion: () => apiRequest<void>("/api/v1/accounts/me", { method: "DELETE" }),
};

export type LaboratoryReport = Schemas["LaboratoryReport"];
export type LaboratoryReportSummary = Schemas["LaboratoryReportSummary"];
export type LaboratoryResult = Schemas["LaboratoryResult"];
/** The generated type marks fields with a default as required; on the wire they are optional. */
export type ReviewRequest = Omit<Schemas["ReviewRequest"], "confirmAllRemaining" | "finalize"> & {
  confirmAllRemaining?: boolean;
  finalize?: boolean;
};
export type Measurement = Schemas["Measurement"];
export type ReferenceRange = Schemas["ReferenceRange"];

export const laboratoryApi = {
  list: (cursor?: string) =>
    apiRequest<{ items: LaboratoryReportSummary[]; nextCursor: string | null }>(
      `/api/v1/laboratory-reports?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ).then((r) => r.data),
  get: (id: string) => apiRequest<LaboratoryReport>(`/api/v1/laboratory-reports/${id}`),
  review: (id: string, body: ReviewRequest, etag: string) =>
    apiRequest<LaboratoryReport>(`/api/v1/laboratory-reports/${id}/review`, { method: "POST", body, ifMatch: etag }),
  pendingCount: () =>
    apiRequest<{ reports: number }>("/api/v1/laboratory-reports/pending-review/count").then((r) => r.data.reports),
};

export interface AnalyteInfo {
  id: string;
  code: string;
  displayName: string;
  category: string;
}

export interface SeriesPoint {
  resultId: string;
  reportId: string;
  collectedAt: string;
  value?: string;
  comparator?: "<" | "<=" | ">" | ">=";
  valueText?: string;
  unit?: string | null;
  referenceRange: { rawText: string | null; low: string | null; high: string | null; appliesTo: string | null };
  flag?: { value: string; source: "LABORATORY" | "COMPUTED" };
  laboratoryName?: string | null;
}

export interface TrackedAnalyte {
  analyte: AnalyteInfo;
  latest: SeriesPoint;
  count: number;
  recent: { collectedAt: string; value: string }[];
}

export interface AnalyteSeries {
  analyte: AnalyteInfo;
  series: { unit: string | null; points: SeriesPoint[] }[];
}

export interface TimelineItem {
  type: "LABORATORY_REPORT" | "DOCUMENT";
  id: string;
  occurredOn: string;
  title?: string;
  status?: string;
  resultCount?: number;
  outOfRangeCount?: number;
}

export const analyticsApi = {
  tracked: () => apiRequest<{ items: TrackedAnalyte[] }>("/api/v1/health-analytics/analytes").then((r) => r.data.items),
  series: (analyteId: string) =>
    apiRequest<AnalyteSeries>(`/api/v1/health-analytics/analytes/${analyteId}/series`).then((r) => r.data),
  timeline: (cursor?: string) =>
    apiRequest<{ items: TimelineItem[]; nextCursor: string | null }>(
      `/api/v1/health-timeline?types=LABORATORY_REPORT&limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ).then((r) => r.data),
};

export type Conversation = Schemas["Conversation"];
export type ConversationDetail = Schemas["ConversationDetail"];
export type UserMessage = Schemas["UserMessage"];
export type AssistantMessage = Schemas["AssistantMessage"];
export type ChatMessage = UserMessage | AssistantMessage;

const conversations = "/api/v1/ai/conversations";

export const assistantApi = {
  list: () =>
    apiRequest<{ items: Conversation[]; nextCursor: string | null }>(`${conversations}?limit=50`).then((r) => r.data.items),
  /** The first 100 messages; longer conversations are out of scope for the MVP screen. */
  get: (id: string) => apiRequest<ConversationDetail>(`${conversations}/${id}?limit=100`).then((r) => r.data),
  create: () => apiRequest<Conversation>(conversations, { method: "POST", body: {} }).then((r) => r.data),
  remove: (id: string) => apiRequest<void>(`${conversations}/${id}`, { method: "DELETE" }),
  ask: (id: string, content: string, idempotencyKey: string) =>
    apiRequest<AssistantMessage>(`${conversations}/${id}/messages`, {
      method: "POST",
      body: { content },
      idempotencyKey,
    }).then((r) => r.data),
};

export type AppNotification = Schemas["Notification"];

export const notificationApi = {
  list: (status?: "UNREAD" | "READ") =>
    apiRequest<{ items: AppNotification[]; nextCursor: string | null }>(
      `/api/v1/notifications?limit=50${status ? `&status=${status}` : ""}`,
    ).then((r) => r.data.items),
  markRead: (id: string) => apiRequest<void>(`/api/v1/notifications/${id}/read`, { method: "POST" }),
};

export type HealthSummary = Schemas["HealthSummary"];
export type ReportComparison = Schemas["ReportComparison"];

export const resultsApi = {
  summary: () => apiRequest<HealthSummary>("/api/v1/health-analytics/summary").then((r) => r.data),
  outOfRange: (cursor?: string) =>
    apiRequest<{ items: LaboratoryResult[]; nextCursor: string | null }>(
      `/api/v1/health-analytics/out-of-range?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ).then((r) => r.data),
  compare: (left: string, right: string) =>
    apiRequest<ReportComparison>(
      `/api/v1/laboratory-reports/comparison?left=${encodeURIComponent(left)}&right=${encodeURIComponent(right)}`,
    ).then((r) => r.data),
};
