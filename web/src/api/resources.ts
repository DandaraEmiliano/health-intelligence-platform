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
