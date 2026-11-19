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
