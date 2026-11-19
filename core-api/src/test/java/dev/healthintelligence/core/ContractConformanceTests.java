package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.healthintelligence.core.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every operation core-api implements must exist in the design contract
 * ({@code contracts/core-api.openapi.yaml}) with the same path, method and operationId (ADR 0009,
 * ADR 0010). The contract may describe operations that are not implemented yet.
 */
class ContractConformanceTests extends IntegrationTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");

    @Autowired
    JsonMapper json;

    @Test
    void implementedOperationsMatchTheContract() throws Exception {
        Map<String, String> implemented = new TreeMap<>();
        JsonNode generated = json.readTree(api().get("/v3/api-docs", null).body());
        generated.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            if (path.getKey().startsWith("/api/") && METHODS.contains(operation.getKey())) {
                implemented.put(operation.getKey().toUpperCase() + " " + path.getKey(),
                        operation.getValue().get("operationId").stringValue());
            }
        }));

        Map<String, String> contract = new TreeMap<>();
        try (InputStream in = Files.newInputStream(Path.of("../contracts/core-api.openapi.yaml"))) {
            Map<String, Object> document = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
            paths.forEach((path, operations) -> operations.forEach((method, operation) -> {
                if (METHODS.contains(method)) {
                    contract.put(method.toUpperCase() + " " + path,
                            String.valueOf(((Map<?, ?>) operation).get("operationId")));
                }
            }));
        }

        assertThat(implemented).isNotEmpty();
        assertThat(contract).containsAllEntriesOf(implemented);
    }
}
