package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response bodies of {@code /api/v1/ai/conversations} (contracts/core-api.openapi.yaml). */
final class AssistantViews {

    private AssistantViews() {
    }

    record ConversationView(UUID id, String title, Instant createdAt, Instant updatedAt) {

        static ConversationView of(Conversation c) {
            return new ConversationView(c.id(), c.title(), c.createdAt(), c.updatedAt());
        }
    }

    record ConversationPage(List<ConversationView> items, String nextCursor) {
    }

    /** Messages are {@link UserMessageView} or {@link AssistantMessageView}, told apart by {@code role}. */
    record ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt, List<Object> messages,
                              String nextCursor) {
    }

    record UserMessageView(UUID messageId, String role, String content, Instant createdAt) {
    }

    /**
     * The structured answer (ADR 0004). {@code facts} are built by the core from the verified results
     * ai-service cited, never from model text; {@code reportId} lets the client link to the exam.
     */
    record AssistantMessageView(UUID messageId, String role, String answer, List<FactView> facts,
                                List<ExplanationView> explanations, String uncertainty, boolean insufficientData,
                                CareView seekProfessionalCare, List<String> suggestedQuestionsForDoctor,
                                SafetyView safety, Instant createdAt) {
    }

    record FactView(UUID resultId, UUID reportId, String label, String value, String unit, String referenceRange,
                    LocalDate collectedAt) {
    }

    record ExplanationView(String text, List<SourceView> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SourceView(String type, String id, String url) {
    }

    record CareView(boolean recommended, String urgency) {
    }

    record SafetyView(boolean refused, String reasonCode) {
    }
}
