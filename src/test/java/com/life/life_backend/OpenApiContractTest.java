package com.life.life_backend;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiContractTest {
    @Test
    void contractParsesAndAllLocalReferencesResolve() throws Exception {
        Map<String, Object> document;
        try (var reader = Files.newBufferedReader(Path.of("docs/openapi.yaml"))) {
            document = new Yaml().load(reader);
        }
        checkReferences(document, document);
        var paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.containsKey("/api/v1/sessions/generated/local")).isTrue();
        assertThat(paths.containsKey("/api/v1/sessions/{sessionId}/step")).isTrue();
        var schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        var countries = (List<String>) ((Map<?, ?>) schemas.get("SupportedCountry")).get("enum");
        var catalog = new com.life.life_backend.statistics.CountryCatalog(new tools.jackson.databind.ObjectMapper());
        assertThat(countries).containsExactlyInAnyOrderElementsOf(catalog.codes());
    }
    private void checkReferences(Object value, Map<String, Object> root) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String reference && reference.startsWith("#/")) {
                Object target = root;
                for (String part : reference.substring(2).split("/")) {
                    assertThat(target).as(reference).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                }
                assertThat(target).as(reference).isNotNull();
            }
            map.values().forEach(child -> checkReferences(child, root));
        } else if (value instanceof List<?> list) list.forEach(child -> checkReferences(child, root));
    }
}
