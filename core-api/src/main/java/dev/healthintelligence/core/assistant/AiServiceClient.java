package dev.healthintelligence.core.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/v1/assistant/answers} on ai-service (contracts/internal), with a
 * client-credentials token of {@code core-api-service} (audience ai-service, ADR 0002). Any failure
 * (timeout, connection, non-2xx, a body that breaks the contract) is {@link Unavailable}: the
 * patient gets 503 and nothing is stored.
 */
@Component
class AiServiceClient {

    static final String SCHEMA_VERSION = "1.0";
    private static final String RESPONSE_SCHEMA = "contracts/internal/assistant-answer-response.v1.schema.json";
    /** Renew the token this long before it expires. */
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    /** The service could not answer; the reason is for logs only. */
    static class Unavailable extends RuntimeException {
        Unavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    // --- request (assistant-answer-request.v1) --------------------------------------------------

    record AnswerRequest(String schemaVersion, String requestId, String patientId, String question,
                         List<Turn> history, FactPack factPack) {
    }

    record Turn(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FactPack(PatientInfo patient, List<ResultFact> results, List<AllergyFact> allergies,
                    List<MedicationFact> medications, List<ConditionFact> conditions) {
    }

    record PatientInfo(Integer ageYears, String biologicalSex) {
    }

    record ResultFact(String factId, String analyteCode, String label, String collectedAt, String kind,
                      String displayValue, String valueNumeric, String comparator, String unit, String referenceRange,
                      String low, String high, String flag, String flagSource, String laboratoryName) {
    }

    record AllergyFact(String substance, String type, String severity) {
    }

    record MedicationFact(String name, String dosageText, String frequencyText, boolean active) {
    }

    record ConditionFact(String description, String status) {
    }

    // --- response (assistant-answer-response.v1) ------------------------------------------------

    record Answer(String schemaVersion, String requestId, String outcome, String answer, List<String> citedFactIds,
                  List<Explanation> explanations, String uncertainty, boolean insufficientData, Care seekProfessionalCare,
                  List<String> suggestedQuestionsForDoctor, Safety safety, String promptVersion, Usage usage) {
    }

    record Explanation(String text, List<Source> sources) {
    }

    record Source(String type, String id) {
    }

    record Care(boolean recommended, String urgency) {
    }

    record Safety(boolean refused, String reasonCode) {
    }

    record Usage(String provider, String model, Integer inputTokens, Integer outputTokens, Integer latencyMs) {
    }

    private final RestClient ai;
    private final RestClient identityProvider;
    private final JsonMapper json;
    private final Schema responseSchema;
    private final Clock clock;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private String token;
    private Instant tokenExpiresAt = Instant.MIN;

    AiServiceClient(@Value("${hip.assistant.ai-service.base-url}") String baseUrl,
                    @Value("${hip.assistant.ai-service.timeout}") Duration timeout,
                    @Value("${hip.assistant.ai-service.token-uri}") String tokenUri,
                    @Value("${hip.assistant.ai-service.client-id}") String clientId,
                    @Value("${hip.assistant.ai-service.client-secret}") String clientSecret,
                    JsonMapper json, Clock clock) throws IOException {
        // HTTP/1.1: the JDK client otherwise asks for an h2c upgrade, which uvicorn rejects with 400.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        // Built here: Spring Boot 4 only auto-configures RestClient.Builder with spring-boot-restclient.
        this.ai = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        JdkClientHttpRequestFactory tokenRequests = new JdkClientHttpRequestFactory(http);
        tokenRequests.setReadTimeout(Duration.ofSeconds(5));
        this.identityProvider = RestClient.builder().requestFactory(tokenRequests).build();
        this.json = json;
        this.clock = clock;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        try (InputStream in = new ClassPathResource(RESPONSE_SCHEMA).getInputStream()) {
            this.responseSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(json.readTree(in));
        }
    }

    Answer answer(AnswerRequest request) {
        String body;
        try {
            body = ai.post()
                    .uri("/internal/v1/assistant/answers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Includes timeouts, refused connections and every non-2xx status. A 401 may be a token
            // revoked early: drop it so the next call asks for a new one.
            invalidateToken();
            throw new Unavailable(e.getClass().getSimpleName(), e);
        }
        return parse(body, request.requestId());
    }

    private Answer parse(String body, String requestId) {
        JsonNode tree;
        try {
            tree = json.readTree(body == null ? "" : body);
        } catch (JacksonException notJson) {
            throw new Unavailable("response is not JSON", notJson);
        }
        List<Error> errors = responseSchema.validate(tree);
        if (!errors.isEmpty()) {
            log.error("ai-service answer breaks the contract: {} violations", errors.size());
            throw new Unavailable("contract violation", null);
        }
        Answer answer = json.treeToValue(tree, Answer.class);
        if (!requestId.equals(answer.requestId())) {
            throw new Unavailable("answer for another request", null);
        }
        return answer;
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now(clock).isBefore(tokenExpiresAt.minus(TOKEN_MARGIN))) {
            return token;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> response;
        try {
            response = identityProvider.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new Unavailable("token request failed: " + e.getClass().getSimpleName(), e);
        }
        if (response == null || !(response.get("access_token") instanceof String value)) {
            throw new Unavailable("no access token from the identity provider", null);
        }
        long expiresIn = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60;
        token = value;
        tokenExpiresAt = Instant.now(clock).plusSeconds(expiresIn);
        return token;
    }

    private synchronized void invalidateToken() {
        token = null;
    }
}
