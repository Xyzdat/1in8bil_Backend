package com.life.life_backend;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.engine.focus.FocusState;
import com.life.life_backend.statistics.StatisticsImporter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class YearFocusIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StatisticsImporter importer;
    @MockitoBean MortalityModel mortality;

    @Test void focusIsOwnedVersionedIdempotentAndSafeDuringPendingDecision() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner);String path=path(born);UUID id=UUID.fromString(born.path("id").asString());
        assertThat(born.path("simulation").path("yearlyFocus").path("selectedId").asString()).isEqualTo("balanced");
        focus(owner,path,key(),1,"learning",409);
        mvc.perform(patch(path+"/focus").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"focusId\":\"home\"}")).andExpect(status().isUnauthorized());
        focus(guest(),path,key(),1,"balanced",404);
        var sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        sim=sim.evolve(38,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),"drawing_choice",null).withFocus(FocusState.start(38));
        // Supply a mother deterministically, keeping the birth fixture independent of sampled household composition.
        sim=sim.evolve(38,List.of(new LocalSimulation.Person("mother","mother","Анна",-300,true,70,70)),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),"drawing_choice",null);
        var state=new LifeState(3,LifeState.Status.ACTIVE,new LifeState.Stats(80,70,50,70,50,0),
                new LifeState.Decision("drawing","Рисовать?",List.of(new LifeState.Option("draw","Рисовать"),new LifeState.Option("blocks","Строить"))),
                json.treeToValue(born.path("state"),LifeState.class).history());
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb where id=?",json.writeValueAsString(sim),json.writeValueAsString(state),id);
        var original=load(owner,path);String token=key();
        var changed=focus(owner,path,token,1,"learning",200);
        assertThat(changed.path("state")).isEqualTo(original.path("state"));
        assertThat(changed.path("simulation").path("elapsedMonths").asInt()).isEqualTo(38);
        assertThat(changed.path("simulation").path("yearlyFocus").path("selectedId").asString()).isEqualTo("learning");
        var options=changed.path("simulation").path("yearlyFocus").path("options");
        var learning=java.util.stream.StreamSupport.stream(options.spliterator(),false).filter(o->o.path("id").asString().equals("learning")).findFirst().orElseThrow();
        assertThat(learning.path("effects").path("stats").path("intelligence").asInt()).isEqualTo(3);
        assertThat(learning.path("effects").path("stats").path("happiness").asInt()).isEqualTo(-1);
        assertThat(focus(owner,path,token,1,"learning",200)).isEqualTo(changed);
        focus(owner,path,token,1,"home",409);
        focus(owner,path,key(),1,"home",409);
        focus(owner,path,key(),2,"unknown",400);
        String before=jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,id);
        assertThat(load(owner,path)).isEqualTo(changed);
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,id)).isEqualTo(before);
        var answered=postCommand(owner,path+"/decisions/drawing/answers","{\"expectedVersion\":2,\"optionId\":\"draw\"}");
        var stepped=postCommand(owner,path+"/step","{\"expectedVersion\":"+answered.path("version").asLong()+"}");
        assertThat(stepped.path("state").path("history").toString()).contains("drawing_return");
        assertThat(stepped.path("simulation").path("yearlyFocus").path("selectedId").asString()).isEqualTo("learning");
        assertThat(load(owner,path)).isEqualTo(stepped);
    }

    @Test void concurrentSelectionsApplyOnceAndOldSavesAreNotEnabled() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner);String path=path(born);UUID id=UUID.fromString(born.path("id").asString());
        var sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        sim=sim.evolve(36,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),null,null).withFocus(FocusState.start(36));
        jdbc.update("update life_session set simulation=?::jsonb where id=?",json.writeValueAsString(sim),id);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->focusStatus(owner,path,1,"learning"));
            var b=pool.submit(()->focusStatus(owner,path,1,"home"));
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        var current=load(owner,path);assertThat(current.path("version").asLong()).isEqualTo(2);
        assertThat(current.path("state").path("stats")).isEqualTo(born.path("state").path("stats"));
        jdbc.update("update life_session set simulation=simulation-'focus' where id=?",id);
        String before=jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,id);
        assertThat(load(owner,path).path("simulation").path("yearlyFocus").isNull()).isTrue();
        focus(owner,path,key(),2,"learning",409);
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,id)).isEqualTo(before);
    }

    private Cookie guest() throws Exception{return MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie"));}
    private JsonNode generate(Cookie owner) throws Exception{return json.readTree(mvc.perform(post("/api/v1/sessions/generated/local").cookie(owner).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content("{\"countryIso3\":\"JPN\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
    private String path(JsonNode born){return "/api/v1/sessions/"+born.path("id").asString();}
    private String key(){return UUID.randomUUID().toString();}
    private JsonNode load(Cookie owner,String path) throws Exception{return json.readTree(mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private JsonNode focus(Cookie owner,String path,String key,long version,String id,int status) throws Exception{
        return json.readTree(mvc.perform(patch(path+"/focus").cookie(owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":"+version+",\"focusId\":\""+id+"\"}")).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    private int focusStatus(Cookie owner,String path,long version,String id) throws Exception{
        return mvc.perform(patch(path+"/focus").cookie(owner).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":"+version+",\"focusId\":\""+id+"\"}")).andReturn().getResponse().getStatus();
    }
    private JsonNode postCommand(Cookie owner,String path,String body) throws Exception{
        return json.readTree(mvc.perform(post(path).cookie(owner).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
