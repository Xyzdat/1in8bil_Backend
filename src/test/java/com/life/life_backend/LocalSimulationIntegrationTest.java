package com.life.life_backend;

import com.life.life_backend.statistics.StatisticsImporter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockCookie;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {"spring.datasource.url=${LIFE_TEST_DB_URL}",
        "spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}", "spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class LocalSimulationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StatisticsImporter importer;

    @Test
    void importsGeneratesRestoresAndPurchasesWithoutRepeatingActions() throws Exception {
        assertThat(importer.importBundled()).isEqualTo(importer.importBundled());
        var guest = MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie"));
        for (String country : new String[]{"RUS", "JPN", "IND"}) {
            String key = UUID.randomUUID().toString();
            String body = "{\"countryIso3\":\"" + country + "\"}";
            var snapshot = send(guest, "/api/v1/sessions/generated/local", key, body, 201);
            assertThat(snapshot.path("character").path("name").asString()).contains(" ");
            for (var person : snapshot.path("simulation").path("family")) assertThat(person.path("name").asString()).contains(" ");
            assertThat(snapshot.path("character").path("birthFacts").path("countryIso3").asString()).isEqualTo(country);
            assertThat(snapshot.path("character").path("birthFacts").path("gdpPerCapita").path("unit").asString()).isEqualTo("USD_CURRENT");
            var details = snapshot.path("character").path("birthDetails");
            assertThat(details.path("mother").isObject() || details.path("father").isObject()).isTrue();
            for (String parent : new String[]{"mother", "father"}) if (details.path(parent).isObject())
                assertThat(details.path(parent).path("occupation").path("label").asString()).isNotBlank();
            String path = "/api/v1/sessions/" + snapshot.path("id").asString();
            assertThat(send(guest, "/api/v1/sessions/generated/local", key, body, 200)).isEqualTo(snapshot);
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            while (snapshot.path("state").path("age").asInt() < 7 && !snapshot.path("state").path("status").asString().equals("COMPLETE")) {
                String commandKey = UUID.randomUUID().toString();
                String command = "{\"expectedVersion\":" + snapshot.path("version").asLong() + "}";
                snapshot = send(guest, path + "/step", commandKey, command, 200);
                assertThat(send(guest, path + "/step", commandKey, command, 200)).isEqualTo(snapshot);
                send(guest, path + "/step", UUID.randomUUID().toString(), command, 409);
                var decision = snapshot.path("state").path("pendingDecision");
                if (!decision.isNull()) {
                    assertThat(snapshot.path("state").path("age").asInt()).isGreaterThanOrEqualTo(3);
                    command = "{\"expectedVersion\":" + snapshot.path("version").asLong() + ",\"optionId\":\"" + decision.path("options").get(0).path("id").asString() + "\"}";
                    String answerPath = path + "/decisions/" + decision.path("id").asString() + "/answers";
                    commandKey = UUID.randomUUID().toString();
                    snapshot = send(guest, answerPath, commandKey, command, 200);
                    assertThat(send(guest, answerPath, commandKey, command, 200)).isEqualTo(snapshot);
                }
                var restored = json.readTree(mvc.perform(get(path).cookie(guest)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(restored).isEqualTo(snapshot);
            }
            if (snapshot.path("state").path("status").asString().equals("COMPLETE")) {
                assertThat(snapshot.path("simulation").path("death").isObject()).isTrue();
                send(guest, path + "/step", UUID.randomUUID().toString(), "{\"expectedVersion\":" + snapshot.path("version").asLong() + "}", 409);
                continue;
            }
            assertThat(snapshot.path("simulation").path("elapsedMonths").asInt()).isEqualTo(84);
            assertThat(snapshot.path("simulation").path("family").size()).isBetween(1, 7);
            String purchase = "{\"expectedVersion\":" + snapshot.path("version").asLong()
                    + ",\"categoryId\":\"pets\",\"itemId\":\"hamster\",\"petName\":\"Пушок\"}";
            key = UUID.randomUUID().toString();
            snapshot = send(guest, path + "/purchases", key, purchase, 200);
            assertThat(snapshot.path("highlights").path("slides").toString()).contains("Пушок");
            assertThat(snapshot.path("highlights").path("status").asString()).isEqualTo("LIVE");
            assertThat(send(guest, path + "/purchases", key, purchase, 200)).isEqualTo(snapshot);
            send(guest, path + "/advance", UUID.randomUUID().toString(), "{\"expectedVersion\":" + snapshot.path("version").asLong() + "}", 409);
        }
    }
    private JsonNode send(Cookie guest, String path, String key, String body, int status) throws Exception {
        return json.readTree(mvc.perform(post(path).cookie(guest).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString());
    }
}
