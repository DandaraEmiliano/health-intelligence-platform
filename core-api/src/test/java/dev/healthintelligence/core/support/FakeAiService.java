package dev.healthintelligence.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stand-in for ai-service and for Keycloak's token endpoint, started once for the shared test
 * context. Every request is checked against contracts/internal/assistant-answer-request.v1 (422 if
 * it breaks it), so drift in what core-api sends fails the tests. Tests choose the next behaviour.
 */
public final class FakeAiService {

    public enum Mode { ANSWER, UNAVAILABLE, SLOW, BREAKS_CONTRACT }

    /** A fact id that is never in a pack: the core must drop it. */
    public static final String UNKNOWN_FACT_ID = "00000000-0000-7000-8000-000000000999";
    public static final String TOKEN = "fake-service-token";

    public static final FakeAiService INSTANCE = new FakeAiService();

    private final JsonMapper json = JsonMapper.builder().build();
    private final Schema requestSchema;
    private final HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile Mode mode = Mode.ANSWER;

    private FakeAiService() {
        try (InputStream in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("../contracts/internal/assistant-answer-request.v1.schema.json"))) {
            requestSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(json.readTree(in));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/token", this::token);
        server.createContext("/internal/v1/assistant/answers", this::answer);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        mode = Mode.ANSWER;
        requests.clear();
        authorizations.clear();
    }

    public void mode(Mode next) {
        mode = next;
    }

    /** Requests that reached the answers endpoint, valid or not, oldest first. */
    public List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    public List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
    }

    private void answer(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().containsKey("Upgrade")) {
            // Like uvicorn: an h2c upgrade request is refused.
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 400, "{\"detail\":\"Unsupported upgrade request\"}");
            return;
        }
        JsonNode request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.add(request);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (!requestSchema.validate(request).isEmpty()) {
            respond(exchange, 422, "{\"detail\":\"request breaks the contract\"}");
            return;
        }
        switch (mode) {
            case UNAVAILABLE -> respond(exchange, 503, "{\"detail\":\"AI_UNAVAILABLE\"}");
            case SLOW -> {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, answerFor(request).toString());
            }
            case BREAKS_CONTRACT -> {
                ObjectNode broken = answerFor(request);
                broken.put("outcome", "SOMETHING_ELSE");
                respond(exchange, 200, broken.toString());
            }
            case ANSWER -> respond(exchange, 200, answerFor(request).toString());
        }
    }

    /** Cites the first result of the pack (if any) and one id that was never in it. */
    private ObjectNode answerFor(JsonNode request) {
        ObjectNode answer = json.createObjectNode();
        answer.put("schemaVersion", "1.0");
        answer.put("requestId", request.get("requestId").stringValue());
        answer.put("outcome", "PASSED");
        answer.put("answer", "Resposta sintética do assistente.");
        ArrayNode cited = answer.putArray("citedFactIds");
        JsonNode results = request.get("factPack").get("results");
        if (!results.isEmpty()) {
            cited.add(results.get(0).get("factId").stringValue());
        }
        cited.add(UNKNOWN_FACT_ID);
        ObjectNode explanation = answer.putArray("explanations").addObject();
        explanation.put("text", "Explicação sintética.");
        explanation.putArray("sources").addObject().put("type", "GLOSSARY").put("id", "cholesterol");
        answer.put("uncertainty", "LOW");
        answer.put("insufficientData", results.isEmpty());
        answer.putObject("seekProfessionalCare").put("recommended", true).put("urgency", "ROUTINE");
        answer.putArray("suggestedQuestionsForDoctor").add("Pergunta sintética?");
        answer.putObject("safety").put("refused", false).putNull("reasonCode");
        answer.put("promptVersion", "fake-1");
        answer.putObject("usage").put("provider", "fake").put("model", "fake-model")
                .put("inputTokens", 10).put("outputTokens", 5).put("latencyMs", 1);
        return answer;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
