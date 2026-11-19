import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { problemStatus } from "./http";
import {
  consentApi,
  healthRecordApi,
  patientApi,
  type ConsentType,
  type HealthRecordKind,
  type PatientCreate,
  type PatientUpdate,
  type RequestOf,
} from "./resources";

export const keys = {
  patient: ["patient"] as const,
  consents: ["consents"] as const,
  records: (kind: HealthRecordKind) => ["health-records", kind] as const,
};

/** The profile with its ETag, or null when onboarding has not created it yet (404). */
export function usePatient() {
  return useQuery({
    queryKey: keys.patient,
    queryFn: async () => {
      try {
        return await patientApi.get();
      } catch (error) {
        if (problemStatus(error) === 404) return null;
        throw error;
      }
    },
  });
}

export function useCreatePatient() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: PatientCreate) => patientApi.create(body),
    onSuccess: (result) => client.setQueryData(keys.patient, result),
  });
}

export function useUpdatePatient() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, etag }: { body: PatientUpdate; etag: string }) => patientApi.update(body, etag),
    onSuccess: (result) => client.setQueryData(keys.patient, result),
    onError: (error) => {
      if (problemStatus(error) === 412) void client.invalidateQueries({ queryKey: keys.patient });
    },
  });
}

export function useConsents() {
  return useQuery({ queryKey: keys.consents, queryFn: consentApi.list });
}

export function useRecordConsents() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (changes: { type: ConsentType; granted: boolean }[]) => {
      for (const change of changes) {
        await consentApi.record(change.type, change.granted);
      }
    },
    onSettled: () => client.invalidateQueries({ queryKey: keys.consents }),
  });
}

export function useHealthRecords<K extends HealthRecordKind>(kind: K, enabled = true) {
  return useQuery({ queryKey: keys.records(kind), queryFn: () => healthRecordApi.list(kind), enabled });
}

export function useSaveHealthRecord<K extends HealthRecordKind>(kind: K) {
  const client = useQueryClient();
  return useMutation({
    /**
     * Creates when there is no id; otherwise replaces with the ETag read when editing began, so a
     * change made elsewhere in the meantime is rejected (412) instead of silently overwritten.
     */
    mutationFn: ({ id, etag, body }: { id?: string; etag?: string; body: RequestOf<K> }) =>
      id ? healthRecordApi.replace(kind, id, body, etag ?? "") : healthRecordApi.create(kind, body),
    onSettled: () => client.invalidateQueries({ queryKey: keys.records(kind) }),
  });
}

export function useDeleteHealthRecord(kind: HealthRecordKind) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => healthRecordApi.remove(kind, id),
    onSettled: () => client.invalidateQueries({ queryKey: keys.records(kind) }),
  });
}
