package dev.healthintelligence.core.assistant;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.assistant.AssistantViews.AssistantMessageView;
import dev.healthintelligence.core.assistant.AssistantViews.ConversationDetail;
import dev.healthintelligence.core.assistant.AssistantViews.ConversationPage;
import dev.healthintelligence.core.assistant.AssistantViews.ConversationView;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.IdempotencyStore;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/ai/conversations")
class ConversationController {

    static final int DEFAULT_CONVERSATIONS = 20;
    static final int DEFAULT_MESSAGES = 50;
    static final int MAX_LIMIT = 100;
    static final int QUESTION_MAX = 2000;

    private final CurrentPatient currentPatient;
    private final AssistantService assistant;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    ConversationController(CurrentPatient currentPatient, AssistantService assistant, IdempotencyStore idempotency,
                           JsonMapper json) {
        this.currentPatient = currentPatient;
        this.assistant = assistant;
        this.idempotency = idempotency;
        this.json = json;
    }

    record CreateConversation(String title) {
    }

    record SendMessage(String content) {
    }

    @PostMapping
    ResponseEntity<ConversationView> createConversation(@RequestBody(required = false) CreateConversation body) {
        String title = body == null ? null : body.title();
        if (title != null && title.strip().length() > Conversation.TITLE_MAX) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("title", "INVALID_LENGTH", "title must have at most 200 characters")));
        }
        Conversation conversation = assistant.create(currentPatient.scope(), title);
        return ResponseEntity.created(location(conversation.id())).body(ConversationView.of(conversation));
    }

    @GetMapping
    ResponseEntity<ConversationPage> listConversations(@RequestParam(name = "cursor", required = false) String cursor,
                                                       @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit(limit, DEFAULT_CONVERSATIONS);
        List<Conversation> rows = assistant.page(currentPatient.scope(), Cursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<Conversation> items = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ConversationPage(items.stream().map(ConversationView::of).toList(), next));
    }

    @GetMapping("/{conversationId}")
    ResponseEntity<ConversationDetail> getConversation(@PathVariable("conversationId") UUID id,
                                                       @RequestParam(name = "cursor", required = false) String cursor,
                                                       @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit(limit, DEFAULT_MESSAGES);
        PatientScope scope = currentPatient.scope();
        Conversation conversation = assistant.get(scope, id);
        List<Message> rows = assistant.messages(scope, id, Cursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<Message> page = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ConversationDetail(conversation.id(), conversation.title(), conversation.createdAt(),
                        conversation.updatedAt(), page.stream().map(assistant::view).toList(), next));
    }

    @DeleteMapping("/{conversationId}")
    ResponseEntity<Void> deleteConversation(@PathVariable("conversationId") UUID id) {
        assistant.delete(currentPatient.scope(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * With {@code Idempotency-Key}, a retry of the same question returns the first answer with
     * {@code Idempotent-Replayed: true} and is neither re-asked nor counted by the rate limit.
     */
    @PostMapping("/{conversationId}/messages")
    ResponseEntity<AssistantMessageView> sendMessage(
            @PathVariable("conversationId") UUID id,
            @RequestBody SendMessage body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        String question = validQuestion(body);
        PatientScope scope = currentPatient.scope();
        if (idempotencyKey == null) {
            return created(assistant.ask(scope, id, question));
        }
        String key = validKey(idempotencyKey);
        String fingerprint = "ai-message:" + id + ":" + sha256(question);
        Optional<IdempotencyStore.StoredResponse> stored = idempotency.begin(scope.user(), key, fingerprint);
        if (stored.isPresent()) {
            return ResponseEntity.status(stored.get().status())
                    .header("Idempotent-Replayed", "true")
                    .cacheControl(CacheControl.noStore())
                    .body(json.readValue(stored.get().body(), AssistantMessageView.class));
        }
        try {
            AssistantMessageView answer = assistant.ask(scope, id, question);
            idempotency.complete(scope.user(), key, new IdempotencyStore.StoredResponse(fingerprint,
                    HttpStatus.CREATED.value(), location(id).toString(), json.writeValueAsString(answer)));
            return created(answer);
        } catch (RuntimeException e) {
            idempotency.abandon(scope.user(), key);
            throw e;
        }
    }

    private static ResponseEntity<AssistantMessageView> created(AssistantMessageView answer) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(answer);
    }

    private static String validQuestion(SendMessage body) {
        String content = body == null ? null : body.content();
        if (content == null || content.isBlank()) {
            throw ProblemException.validation(List.of(new FieldViolation("content", "REQUIRED", "content is required")));
        }
        if (content.length() > QUESTION_MAX) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("content", "INVALID_LENGTH", "content must have at most 2000 characters")));
        }
        return content.strip();
    }

    private static int limit(Integer limit, int defaultLimit) {
        int size = limit == null ? defaultLimit : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100")));
        }
        return size;
    }

    private static URI location(UUID conversationId) {
        return URI.create("/api/v1/ai/conversations/" + conversationId);
    }

    private static String validKey(String key) {
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException invalid) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("Idempotency-Key", "INVALID_FORMAT", "Idempotency-Key must be a UUID")));
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
