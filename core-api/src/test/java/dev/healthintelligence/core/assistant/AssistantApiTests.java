package dev.healthintelligence.core.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import dev.healthintelligence.core.laboratory.ReportTestSupport;
import dev.healthintelligence.core.support.FakeAiService;
import dev.healthintelligence.core.support.FakeAiService.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AssistantApiTests extends ReportTestSupport {

    static final String CONVERSATIONS = "/api/v1/ai/conversations";

    final FakeAiService ai = FakeAiService.INSTANCE;

    @BeforeEach
    void freshFake() {
        ai.reset();
    }

    @Test
    void answersFromVerifiedResultsOnlyAndStoresBothTurns() throws Exception {
        String reportId = ingest(result("SUCCEEDED"));
        String conversation = newConversation();

        // Review not finished: nothing of the report may reach the AI (ADR 0006).
        assertThat(ask(conversation, "Como está meu colesterol?").statusCode()).isEqualTo(201);
        assertThat(ai.requests().getLast().get("factPack").get("results").isEmpty()).isTrue();

        finishReview(reportId);
        HttpResponse<String> response = ask(conversation, "E agora, como está meu colesterol?");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode request = ai.requests().getLast();
        assertThat(ai.authorizations().getLast()).isEqualTo("Bearer " + FakeAiService.TOKEN);
        assertThat(request.get("patientId").stringValue()).isEqualTo(patientId);
        assertThat(request.get("factPack").get("results").size()).isEqualTo(Integer.parseInt(single("""
                select count(*)::text from core.laboratory_result
                where patient_id = ?::uuid and review_status in ('VERIFIED', 'CORRECTED')""", patientId)));
        assertThat(request.get("factPack").get("patient").get("ageYears").intValue()).isBetween(30, 60);
        assertThat(request.get("history").size()).isEqualTo(2);

        JsonNode answer = json.readTree(response.body());
        assertThat(answer.get("role").stringValue()).isEqualTo("ASSISTANT");
        assertThat(answer.get("facts").size()).as("the unknown cited id is dropped").isEqualTo(1);
        JsonNode fact = answer.get("facts").get(0);
        assertThat(fact.get("resultId").stringValue())
                .isEqualTo(request.get("factPack").get("results").get(0).get("factId").stringValue());
        assertThat(fact.get("reportId").stringValue()).isEqualTo(reportId);
        assertThat(fact.get("value").stringValue()).isNotBlank();
        assertThat(answer.get("explanations").get(0).get("sources").get(0).get("type").stringValue()).isEqualTo("GLOSSARY");
        assertThat(answer.get("seekProfessionalCare").get("urgency").stringValue()).isEqualTo("ROUTINE");

        JsonNode detail = json.readTree(api().get(CONVERSATIONS + "/" + conversation, token).body());
        assertThat(detail.get("title").stringValue()).isEqualTo("Como está meu colesterol?");
        assertThat(detail.get("messages").size()).isEqualTo(4);
        assertThat(detail.get("messages").get(2).get("role").stringValue()).isEqualTo("USER");
        assertThat(detail.get("messages").get(2).get("content").stringValue()).isEqualTo("E agora, como está meu colesterol?");
        assertThat(detail.get("messages").get(3)).isEqualTo(answer);
        assertThat(single("""
                select guardrail_outcome || ':' || prompt_version || ':' || model from core.ai_message
                where id = ?::uuid""", answer.get("messageId").stringValue())).isEqualTo("PASSED:fake-1:fake-model");
    }

    @Test
    void withoutAiConsentNothingIsAsked() {
        String conversation = newConversation();
        api().postJson("/api/v1/consents", token, "{\"type\":\"AI_PROCESSING\",\"granted\":false,\"documentVersion\":\"ai-2026.1\"}");

        HttpResponse<String> asked = ask(conversation, "Oi");
        HttpResponse<String> created = api().postJson(CONVERSATIONS, token, "{}");

        assertThat(asked.statusCode()).isEqualTo(403);
        assertThat(code(asked)).isEqualTo("AI_CONSENT_REQUIRED");
        assertThat(created.statusCode()).isEqualTo(403);
        assertThat(code(created)).isEqualTo("AI_CONSENT_REQUIRED");
        assertThat(ai.requests()).isEmpty();
    }

    @Test
    void whenAiServiceCannotAnswerThePatientGets503AndNothingIsStored() {
        String conversation = newConversation();
        for (Mode failure : new Mode[] {Mode.UNAVAILABLE, Mode.SLOW, Mode.BREAKS_CONTRACT}) {
            ai.mode(failure);
            HttpResponse<String> response = ask(conversation, "Oi");

            assertThat(response.statusCode()).as(failure.name()).isEqualTo(503);
            assertThat(code(response)).isEqualTo("AI_UNAVAILABLE");
            assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
        }
        JsonNode detail = json.readTree(api().get(CONVERSATIONS + "/" + conversation, token).body());
        assertThat(detail.get("messages").isEmpty()).isTrue();
        assertThat(detail.get("title").isNull()).isTrue();
    }

    @Test
    void questionsAreLimitedPerUserAndUnansweredOnesAreNotCounted() {
        String conversation = newConversation();
        ai.mode(Mode.UNAVAILABLE);
        assertThat(ask(conversation, "Oi").statusCode()).isEqualTo(503);
        ai.mode(Mode.ANSWER);
        for (int i = 0; i < 5; i++) {
            assertThat(ask(conversation, "Pergunta " + i).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> limited = ask(conversation, "Mais uma");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 3600L);
        // Another patient has their own budget.
        String other = tokens.newPatient();
        api().postJson("/api/v1/consents", other, "{\"type\":\"AI_PROCESSING\",\"granted\":true,\"documentVersion\":\"ai-2026.1\"}");
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1990-01-01\"}");
        String theirs = json.readTree(api().postJson(CONVERSATIONS, other, "{}").body()).get("id").stringValue();
        assertThat(api().postJson(CONVERSATIONS + "/" + theirs + "/messages", other, "{\"content\":\"Oi\"}").statusCode())
                .isEqualTo(201);
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyReplaysTheAnswer() {
        String conversation = newConversation();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = askWithKey(conversation, "Oi", key);
        HttpResponse<String> retry = askWithKey(conversation, "Oi", key);
        HttpResponse<String> reused = askWithKey(conversation, "Outra pergunta", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readTree(retry.body())).isEqualTo(json.readTree(first.body()));
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(ai.requests()).hasSize(1);
        assertThat(json.readTree(api().get(CONVERSATIONS + "/" + conversation, token).body()).get("messages").size()).isEqualTo(2);
    }

    @Test
    void invalidQuestionsAreRejected() {
        String conversation = newConversation();

        assertThat(ask(conversation, "   ").statusCode()).isEqualTo(400);
        assertThat(ask(conversation, "a".repeat(2001)).statusCode()).isEqualTo(400);
        assertThat(api().postJson(CONVERSATIONS + "/" + conversation + "/messages", token,
                "{\"content\":\"Oi\",\"patientId\":\"" + patientId + "\"}").statusCode()).isEqualTo(400);
        assertThat(askWithKey(conversation, "Oi", "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(ask(UUID.randomUUID().toString(), "Oi").statusCode()).isEqualTo(404);
        assertThat(ai.requests()).isEmpty();
    }

    @Test
    void conversationsAreListedNewestFirstPagedAndDeleted() {
        String older = newConversation();
        String newer = newConversation();
        ask(older, "Pergunta que move a conversa para o topo");

        JsonNode page = json.readTree(api().get(CONVERSATIONS + "?limit=1", token).body());
        assertThat(page.get("items").get(0).get("id").stringValue()).isEqualTo(older);
        JsonNode next = json.readTree(api().get(CONVERSATIONS + "?limit=1&cursor=" + page.get("nextCursor").stringValue(), token).body());
        assertThat(next.get("items").get(0).get("id").stringValue()).isEqualTo(newer);
        assertThat(next.get("nextCursor").isNull()).isTrue();

        assertThat(api().delete(CONVERSATIONS + "/" + older, token).statusCode()).isEqualTo(204);
        assertThat(api().get(CONVERSATIONS + "/" + older, token).statusCode()).isEqualTo(404);
        assertThat(api().delete(CONVERSATIONS + "/" + older, token).statusCode()).isEqualTo(404);
    }

    @Test
    void messagesArePaged() {
        String conversation = newConversation();
        ask(conversation, "Primeira");
        ask(conversation, "Segunda");

        JsonNode first = json.readTree(api().get(CONVERSATIONS + "/" + conversation + "?limit=3", token).body());
        JsonNode rest = json.readTree(api().get(CONVERSATIONS + "/" + conversation + "?limit=3&cursor="
                + first.get("nextCursor").stringValue(), token).body());

        assertThat(first.get("messages").size()).isEqualTo(3);
        assertThat(rest.get("messages").size()).isEqualTo(1);
        assertThat(rest.get("messages").get(0).get("role").stringValue()).isEqualTo("ASSISTANT");
        assertThat(rest.get("nextCursor").isNull()).isTrue();
    }

    private String newConversation() {
        HttpResponse<String> created = api().postJson(CONVERSATIONS, token, "{}");
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).get("id").stringValue();
    }

    private HttpResponse<String> ask(String conversation, String question) {
        return api().postJson(CONVERSATIONS + "/" + conversation + "/messages", token,
                json.writeValueAsString(java.util.Map.of("content", question)));
    }

    private HttpResponse<String> askWithKey(String conversation, String question, String key) {
        return api().postJsonWithHeaders(CONVERSATIONS + "/" + conversation + "/messages", token,
                json.writeValueAsString(java.util.Map.of("content", question)), "Idempotency-Key", key);
    }

    private String code(HttpResponse<String> response) {
        return json.readTree(response.body()).get("code").stringValue();
    }
}
