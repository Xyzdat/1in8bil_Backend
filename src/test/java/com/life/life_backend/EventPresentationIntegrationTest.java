package com.life.life_backend;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.LocalSimulation;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class EventPresentationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void oldSaveGetsReadOnlyPreviewsAndCurrentImportanceThroughHttp() throws Exception {
        var owner=MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie"));
        var born=send(owner,"/api/v1/sessions",UUID.randomUUID().toString(),"""
                {"character":{"name":"Анна Мария Иванова","gender":"Женщина","birthYear":2026,"birthplace":{"countryCode":"RU","country":"Россия"}},
                 "startingStats":{"health":80,"intelligence":50,"appearance":60}}
                """,201);
        UUID id=UUID.fromString(born.path("id").asString()); String path="/api/v1/sessions/"+id;
        var sim=new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),38,
                List.of(new LocalSimulation.Person("mother","mother","Мария Иванова",-300,true,99,99)),Map.of(),Set.of(),Map.of(),List.of(),"drawing_choice");
        String legacyState="""
                {"age":3,"status":"ACTIVE","stats":{"health":80,"happiness":70,"intelligence":50,"relationships":99,"appearance":60,"aura":0},
                 "pendingDecision":{"id":"local-38-drawing_choice","title":"Рисовать?","options":[{"id":"draw","label":"Рисовать"},{"id":"blocks","label":"Строить"}]},
                 "history":[{"id":"birth","age":0,"title":"Рождение","text":"Начало"}]}
                """;
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb where id=?",json.writeValueAsString(sim),legacyState,id);
        String before=row(id);
        var shown=load(owner,path);
        assertThat(shown.path("character").path("name").asString()).isEqualTo("Анна Мария Иванова");
        assertThat(shown.path("simulation").path("family").get(0).path("name").asString()).isEqualTo("Мария Иванова");
        assertThat(shown.path("state").path("history").get(0).path("importance").asInt()).isEqualTo(50);
        var effects=shown.path("state").path("pendingDecision").path("options").get(0).path("effects");
        assertThat(effects.path("scope").asString()).isEqualTo("IMMEDIATE");
        assertThat(effects.path("stats").path("relationships").asInt()).isEqualTo(1);
        assertThat(effects.path("closeness").path("mother").asInt()).isEqualTo(1);
        assertThat(load(owner,path)).isEqualTo(shown);
        assertThat(row(id)).isEqualTo(before);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var stranger=MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getHeader("Set-Cookie"));
        mvc.perform(get(path).cookie(stranger)).andExpect(status().isNotFound());

        String key=UUID.randomUUID().toString(), route=path+"/decisions/local-38-drawing_choice/answers";
        String command="{\"expectedVersion\":"+shown.path("version").asLong()+",\"optionId\":\"draw\"}";
        var result=send(owner,route,key,command,200);
        assertThat(result.path("state").path("stats").path("relationships").asInt()).isEqualTo(100);
        assertThat(result.path("simulation").path("family").get(0).path("closeness").asInt()).isEqualTo(100);
        var event=result.path("state").path("history").get(1);
        assertThat(event.path("importanceSource").asString()).isEqualTo("LIFE_STORY");
        assertThat(event.path("importance").asInt()).isPositive();
        assertThat(result.path("highlights").path("decisionCount").asInt()).isEqualTo(1);
        assertThat(send(owner,route,key,command,200)).isEqualTo(result);
        assertThat(load(owner,path)).isEqualTo(result);
        // Response annotations and hypothetical outcomes were never written into the source history/options.
        assertThat(jdbc.queryForObject("select state->'history'->1->>'importance' from life_session where id=?",String.class,id)).isNull();
    }

    private String row(UUID id) { return jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,id); }
    private JsonNode load(Cookie owner,String path) throws Exception {
        return json.readTree(mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode send(Cookie owner,String path,String key,String body,int status) throws Exception {
        return json.readTree(mvc.perform(post(path).cookie(owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
}
