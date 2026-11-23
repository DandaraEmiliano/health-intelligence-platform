package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One turn. A USER message holds the question; an ASSISTANT message holds the answer text and the
 * whole structured answer as returned to the patient, plus the guardrail outcome and call metrics.
 */
@Entity
@Table(name = "ai_message")
class Message {

    enum Role { USER, ASSISTANT }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_payload", updatable = false)
    private String structuredPayload;

    @Column(name = "prompt_version", updatable = false)
    private String promptVersion;

    @Column(updatable = false)
    private String model;

    @Column(name = "input_tokens", updatable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Integer outputTokens;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "guardrail_outcome", updatable = false)
    private String guardrailOutcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
    }

    private Message(UUID id, UUID conversationId, PatientId patientId, Role role, String content, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.patientId = patientId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    static Message question(UUID id, Conversation conversation, String content, Instant createdAt) {
        return new Message(id, conversation.id(), conversation.patientId(), Role.USER, content, createdAt);
    }

    static Message answer(UUID id, Conversation conversation, AiServiceClient.Answer answer, String payload,
                          Instant createdAt) {
        Message message = new Message(id, conversation.id(), conversation.patientId(), Role.ASSISTANT,
                answer.answer(), createdAt);
        message.structuredPayload = payload;
        message.promptVersion = answer.promptVersion();
        message.guardrailOutcome = answer.outcome();
        if (answer.usage() != null) {
            message.model = answer.usage().model();
            message.inputTokens = answer.usage().inputTokens();
            message.outputTokens = answer.usage().outputTokens();
            message.latencyMs = answer.usage().latencyMs();
        }
        return message;
    }

    UUID id() { return id; }
    Role role() { return role; }
    String content() { return content; }
    String structuredPayload() { return structuredPayload; }
    String guardrailOutcome() { return guardrailOutcome; }
    Instant createdAt() { return createdAt; }
}
