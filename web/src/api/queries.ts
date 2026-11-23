import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { problemStatus } from "./http";
import {
  consentApi,
  documentApi,
  laboratoryApi,
  analyticsApi,
  assistantApi,
  notificationApi,
  resultsApi,
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
  tracked: ["analytics", "tracked"] as const,
  series: (id: string) => ["analytics", "series", id] as const,
  timeline: ["timeline"] as const,
  conversations: ["conversations"] as const,
  notifications: ["notifications"] as const,
  conversation: (id: string) => ["conversations", id] as const,
  summary: ["analytics", "summary"] as const,
  outOfRange: ["analytics", "out-of-range"] as const,
  comparison: (left: string, right: string) => ["comparison", left, right] as const,
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
      void client.invalidateQueries({ queryKey: ["analytics"] });
      void client.invalidateQueries({ queryKey: keys.timeline });
    },
    onError: (error) => {
      // Someone (another tab) changed the report: reload it so the patient sees the current state.
      if (problemStatus(error) === 412) void client.invalidateQueries({ queryKey: keys.report(id) });
    },
  });
}

export function useTrackedAnalytes() {
  return useQuery({ queryKey: keys.tracked, queryFn: analyticsApi.tracked });
}

export function useAnalyteSeries(analyteId: string) {
  return useQuery({ queryKey: keys.series(analyteId), queryFn: () => analyticsApi.series(analyteId) });
}

export function useTimeline() {
  return useInfiniteQuery({
    queryKey: keys.timeline,
    queryFn: ({ pageParam }) => analyticsApi.timeline(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

export function useConversations(enabled = true) {
  return useQuery({ queryKey: keys.conversations, queryFn: assistantApi.list, enabled });
}

export function useConversation(id: string | undefined) {
  return useQuery({
    queryKey: keys.conversation(id ?? ""),
    queryFn: () => assistantApi.get(id!),
    enabled: Boolean(id),
  });
}

export function useCreateConversation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: assistantApi.create,
    onSuccess: () => client.invalidateQueries({ queryKey: keys.conversations, exact: true }),
  });
}

export function useDeleteConversation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => assistantApi.remove(id),
    onSuccess: (_result, id) => client.removeQueries({ queryKey: keys.conversation(id) }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.conversations, exact: true }),
  });
}

/**
 * Asks a question. The caller keeps the idempotency key of a failed attempt and passes it again
 * on "try again", so a request that did reach the core is replayed instead of asked twice.
 */
export function useAsk() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ conversationId, content, idempotencyKey }: { conversationId: string; content: string; idempotencyKey: string }) =>
      assistantApi.ask(conversationId, content, idempotencyKey),
    onSuccess: (_answer, { conversationId }) => {
      void client.invalidateQueries({ queryKey: keys.conversation(conversationId) });
      void client.invalidateQueries({ queryKey: keys.conversations, exact: true });
    },
  });
}

/** Latest notifications; refreshed every 30 s so a finished extraction shows up without reloading. */
export function useNotifications() {
  return useQuery({ queryKey: keys.notifications, queryFn: () => notificationApi.list(), refetchInterval: 30_000 });
}

export function useMarkNotificationsRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) await notificationApi.markRead(id);
    },
    onSettled: () => client.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export function useHealthSummary() {
  return useQuery({ queryKey: keys.summary, queryFn: resultsApi.summary });
}

export function useOutOfRange() {
  return useInfiniteQuery({
    queryKey: keys.outOfRange,
    queryFn: ({ pageParam }) => resultsApi.outOfRange(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

export function useComparison(left: string | null, right: string | null) {
  return useQuery({
    queryKey: keys.comparison(left ?? "", right ?? ""),
    queryFn: () => resultsApi.compare(left!, right!),
    enabled: Boolean(left && right && left !== right),
  });
}
