package dev.healthintelligence.core.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.assistant.AiServiceClient.Answer;
import dev.healthintelligence.core.assistant.AiServiceClient.AnswerRequest;
import dev.healthintelligence.core.assistant.AiServiceClient.Turn;
import dev.healthintelligence.core.assistant.AssistantViews.AssistantMessageView;
import dev.healthintelligence.core.assistant.AssistantViews.CareView;
import dev.healthintelligence.core.assistant.AssistantViews.ExplanationView;
import dev.healthintelligence.core.assistant.AssistantViews.FactView;
import dev.healthintelligence.core.assistant.AssistantViews.SafetyView;
import dev.healthintelligence.core.assistant.AssistantViews.SourceView;
import dev.healthintelligence.core.assistant.AssistantViews.UserMessageView;
import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.consent.ConsentType;
import dev.healthintelligence.core.consent.Consents;
import dev.healthintelligence.core.laboratory.VerifiedResults.Point;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversations and questions (ADR 0004). A question is answered outside any database transaction
 * (the call to ai-service may take seconds); both turns are stored together only once the answer
 * arrives, so a question ai-service could not answer leaves nothing behind.
 */
@Service
class AssistantService {

    static final String NOT_FOUND = "CONVERSATION_NOT_FOUND";
    static final String CONSENT_REQUIRED = "AI_CONSENT_REQUIRED";
    static final String UNAVAILABLE = "AI_UNAVAILABLE";
    /** Turns of history sent with a question (contract maximum). */
    static final int HISTORY_TURNS = 10;
    static final int HISTORY_TURN_MAX = 4000;
    static final long RETRY_AFTER_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final FactPackBuilder factPacks;
    private final AiServiceClient ai;
    private final AssistantRateLimit rateLimit;
    private final Consents consents;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;

    AssistantService(ConversationRepository conversations, MessageRepository messages, FactPackBuilder factPacks,
                     AiServiceClient ai, AssistantRateLimit rateLimit, Consents consents, AuditLog auditLog,
                     TransactionTemplate transaction, JsonMapper json, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.factPacks = factPacks;
        this.ai = ai;
        this.rateLimit = rateLimit;
        this.consents = consents;
        this.auditLog = auditLog;
        this.transaction = transaction;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    Conversation create(PatientScope scope, String title) {
        requireConsent(scope);
        Conversation conversation = conversations.saveAndFlush(new Conversation(Ids.newId(), scope.patient(),
                title == null || title.isBlank() ? null : title.strip(), now()));
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), "AI_CONVERSATION_CREATED", "AI_CONVERSATION",
                conversation.id(), scope.patient().value(), Map.of()));
        return conversation;
    }

    @Transactional(readOnly = true)
    List<Conversation> page(PatientScope scope, Cursor after, int limit) {
        return conversations.page(scope.patient(), after == null ? null : after.at(), after == null ? null : after.id(),
                Limit.of(limit + 1));
    }

    @Transactional(readOnly = true)
    Conversation get(PatientScope scope, UUID id) {
        return conversations.findOwned(id, scope.patient()).orElseThrow(() -> ProblemException.notFound(NOT_FOUND));
    }

    /** One page of messages, oldest first, as returned to the patient; {@code limit + 1} rows to detect more. */
    @Transactional(readOnly = true)
    List<Message> messages(PatientScope scope, UUID conversationId, Cursor after, int limit) {
        get(scope, conversationId);
        return messages.page(conversationId, scope.patient(), after == null ? null : after.at(),
                after == null ? null : after.id(), Limit.of(limit + 1));
    }

    Object view(Message message) {
        if (message.role() == Message.Role.USER) {
            return new UserMessageView(message.id(), "USER", message.content(), message.createdAt());
        }
        return json.readValue(message.structuredPayload(), AssistantMessageView.class);
    }

    @Transactional
    void delete(PatientScope scope, UUID id) {
        Conversation conversation = get(scope, id);
        conversations.delete(conversation);
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), "AI_CONVERSATION_DELETED", "AI_CONVERSATION",
                conversation.id(), scope.patient().value(), Map.of()));
    }

    /** Asks ai-service and stores both turns. 503 {@code AI_UNAVAILABLE} when it cannot answer. */
    AssistantMessageView ask(PatientScope scope, UUID conversationId, String question) {
        requireConsent(scope);
        get(scope, conversationId);
        AssistantRateLimit.Permit permit = rateLimit.acquire(scope.user());
        Instant askedAt = now();

        FactPackBuilder.Built built = factPacks.build(scope, question);
        String requestId = Ids.newId().toString();
        Answer answer;
        try {
            answer = ai.answer(new AnswerRequest(AiServiceClient.SCHEMA_VERSION, requestId,
                    scope.patient().value().toString(), question, history(scope, conversationId), built.pack()));
        } catch (AiServiceClient.Unavailable e) {
            permit.release();
            log.warn("ai-service could not answer request {}: {}", requestId, e.getMessage());
            throw ProblemException.retryLater(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "AI service unavailable",
                    RETRY_AFTER_SECONDS);
        }

        Instant answeredAt = now();
        if (!answeredAt.isAfter(askedAt)) {
            answeredAt = askedAt.plus(1, ChronoUnit.MICROS);
        }
        AssistantMessageView view = view(Ids.newId(), answer, built, answeredAt);
        String payload = json.writeValueAsString(view);
        Instant storedAt = answeredAt;
        transaction.executeWithoutResult(status -> {
            // Read again: the conversation may have been deleted while ai-service was answering.
            Conversation conversation = get(scope, conversationId);
            messages.save(Message.question(Ids.newId(), conversation, question, askedAt));
            messages.save(Message.answer(view.messageId(), conversation, answer, payload, storedAt));
            conversation.exchanged(question, storedAt);
            conversations.saveAndFlush(conversation);
            auditLog.record(AuditRecord.userSuccess(scope.user().value(), "AI_QUESTION_ANSWERED", "AI_CONVERSATION",
                    conversation.id(), scope.patient().value(),
                    Map.of("outcome", answer.outcome(), "citedFacts", String.valueOf(view.facts().size()))));
        });
        return view;
    }

    private void requireConsent(PatientScope scope) {
        if (!consents.isGranted(scope.user(), ConsentType.AI_PROCESSING)) {
            throw ProblemException.forbidden(CONSENT_REQUIRED);
        }
    }

    /** The last turns of the conversation, oldest first, as plain text. */
    private List<Turn> history(PatientScope scope, UUID conversationId) {
        List<Message> latest = new ArrayList<>(messages.latest(conversationId, scope.patient(), Limit.of(HISTORY_TURNS)));
        java.util.Collections.reverse(latest);
        return latest.stream().map(m -> new Turn(m.role().name(), cut(m.content(), HISTORY_TURN_MAX))).toList();
    }

    /**
     * Patient-facing answer. Facts come from the fact pack by cited id; an id that was not in the pack
     * is dropped (ai-service checks this too), so nothing outside the patient's verified data is shown.
     */
    static AssistantMessageView view(UUID messageId, Answer answer, FactPackBuilder.Built built, Instant createdAt) {
        List<FactView> facts = new ArrayList<>();
        for (String cited : new LinkedHashSet<>(answer.citedFactIds())) {
            Point point = built.byFactId().get(UUID.fromString(cited));
            if (point == null) {
                log.warn("ai-service cited a fact that was not in the pack; dropped");
                continue;
            }
            facts.add(new FactView(point.resultId(), point.reportId(), FactPackBuilder.label(point),
                    FactPackBuilder.displayValue(point), point.unit(), point.rangeText(), point.collectedAt()));
        }
        List<ExplanationView> explanations = answer.explanations().stream()
                .map(e -> new ExplanationView(e.text(), e.sources().stream()
                        .map(s -> new SourceView(s.type(), s.id(), null)).toList()))
                .toList();
        return new AssistantMessageView(messageId, "ASSISTANT", answer.answer(), facts, explanations,
                answer.uncertainty(), answer.insufficientData(),
                new CareView(answer.seekProfessionalCare().recommended(), answer.seekProfessionalCare().urgency()),
                answer.suggestedQuestionsForDoctor(),
                new SafetyView(answer.safety().refused(), answer.safety().reasonCode()), createdAt);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
