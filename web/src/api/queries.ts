import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { problemStatus } from "./http";
import {
  consentApi,
  documentApi,
  laboratoryApi,
  healthRecordApi,
  patientApi,
  type ConsentType,
  type HealthRecordKind,
  type PatientCreate,
  type PatientUpdate,
  type RequestOf,
  type ReviewRequest,
} from "./resources";

export const keys = {
  patient: ["patient"] as const,
  consents: ["consents"] as const,
  records: (kind: HealthRecordKind) => ["health-records", kind] as const,
  documents: ["documents"] as const,
  reports: ["laboratory-reports"] as const,
  report: (id: string) => ["laboratory-reports", id] as const,
  pendingReviews: ["laboratory-reports", "pending-count"] as const,
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

/** Pages of documents, newest first. Refreshes every 5 s while any is waiting to be read. */
export function useDocuments() {
  return useInfiniteQuery({
    queryKey: keys.documents,
    queryFn: ({ pageParam }) => documentApi.list(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.items.some((item) => item.status === "PROCESSING")) ? 5000 : false,
  });
}

export function useDeleteDocument() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => documentApi.remove(id),
    onSettled: () => client.invalidateQueries({ queryKey: keys.documents }),
  });
}

export function useReports() {
  return useInfiniteQuery({
    queryKey: keys.reports,
    queryFn: ({ pageParam }) => laboratoryApi.list(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

/** The report with its ETag; every review response replaces both. */
export function useReport(id: string) {
  return useQuery({ queryKey: keys.report(id), queryFn: () => laboratoryApi.get(id) });
}

export function usePendingReviews() {
  return useQuery({ queryKey: keys.pendingReviews, queryFn: laboratoryApi.pendingCount });
}

export function useReview(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, etag }: { body: ReviewRequest; etag: string }) => laboratoryApi.review(id, body, etag),
    onSuccess: (result) => {
      client.setQueryData(keys.report(id), result);
      void client.invalidateQueries({ queryKey: keys.reports, exact: true });
      void client.invalidateQueries({ queryKey: keys.pendingReviews });
    },
    onError: (error) => {
      // Someone (another tab) changed the report: reload it so the patient sees the current state.
      if (problemStatus(error) === 412) void client.invalidateQueries({ queryKey: keys.report(id) });
    },
  });
}
