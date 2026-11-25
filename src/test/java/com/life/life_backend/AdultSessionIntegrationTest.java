package com.life.life_backend;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.engine.local.MortalityModel;
import com.life.life_backend.engine.adult.AdultState;
import com.life.life_backend.engine.adult.ContactState;
import tools.jackson.databind.node.ObjectNode;
import com.life.life_backend.statistics.StatisticsImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class AdultSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StatisticsImporter importer;
    @Autowired MortalityModel mortality;
    @Test void keyDecisionsAreOwnedIdempotentPersistedAndFinalizedWithTheLife() throws Exception {
        importer.importBundled();var owner=guest();
        var current=send(owner,"/api/v1/sessions/generated/local",UUID.randomUUID().toString(),"{\"countryIso3\":\"JPN\"}",201);
        UUID id=UUID.fromString(current.path("id").asString());String path="/api/v1/sessions/"+id;
        assertThat(current.path("highlights").path("status").asString()).isEqualTo("LIVE");
        var sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        long safeSeed=0;
        for(long seed=1;seed<1000&&safeSeed==0;seed++) {
            boolean survives=true;
            for(int month=0;month<96;month+=2) {
                var candidate=new LocalSimulation(seed,sim.engineVersion(),sim.statisticsRelease(),sim.request(),month,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),null,sim.birthFacts(),null);
                if(mortality.dies(candidate,"JPN",sim.birthFacts().sex(),0,0,"character")){survives=false;break;}
            }
            if(survives)safeSeed=seed;
        }
        assertThat(safeSeed).isPositive();
        jdbc.update("update life_session set simulation=jsonb_set(simulation,'{seed}',to_jsonb(?::bigint)) where id=?",safeSeed,id);
        for(int i=0;i<100&&(current.path("state").path("age").asInt()<8||current.path("state").path("pendingDecision").isObject());i++) {
            var decision=current.path("state").path("pendingDecision");boolean answering=decision.isObject();
            String route=answering?path+"/decisions/"+decision.path("id").asString()+"/answers":path+"/step";
            String body="{\"expectedVersion\":"+current.path("version").asLong()+(answering?",\"optionId\":\""+decision.path("options").get(0).path("id").asString()+"\"":"")+"}";
            String key=UUID.randomUUID().toString();current=send(owner,route,key,body,200);
            assertThat(send(owner,route,key,body,200)).isEqualTo(current);
            assertThat(load(owner,path)).isEqualTo(current);
        }
        assertThat(current.path("highlights").path("decisionCount").asInt()).isPositive();
        var stored=json.readValue(jdbc.queryForObject("select life_highlights::text from life_session where id=?",String.class,id),com.life.life_backend.highlights.LifeHighlights.class);
        assertThat(stored.candidates()).anyMatch(c->!c.evidence().isEmpty());
        assertThat(stored.candidates()).allMatch(c->!c.decisions().getFirst().offeredOptions().isEmpty());
        send(owner,path+"/step",UUID.randomUUID().toString(),"{\"expectedVersion\":1}",409);
        assertThat(load(owner,path)).isEqualTo(current);
        mvc.perform(get(path).cookie(guest())).andExpect(status().isNotFound());
        // Jump only this isolated fixture forward to verify finalization in the same transaction as death.
        sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        LocalSimulation terminal=null;
        for(long seed=1;seed<10000;seed++) {
            var candidate=new LocalSimulation(seed,sim.engineVersion(),sim.statisticsRelease(),sim.request(),1200,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),null,sim.birthFacts(),null);
            if(mortality.dies(candidate,"JPN",sim.birthFacts().sex(),0,20,"character")){terminal=candidate;break;}
        }
        assertThat(terminal).isNotNull();
        var oldState=json.treeToValue(current.path("state"),LifeState.class);
        var state=new LifeState(100,LifeState.Status.ACTIVE,new LifeState.Stats(20,50,50,50,50,0),null,oldState.history());
        jdbc.update("update life_session set state=?::jsonb,simulation=?::jsonb where id=?",json.writeValueAsString(state),json.writeValueAsString(terminal),id);
        String body="{\"expectedVersion\":"+current.path("version").asLong()+"}",key=UUID.randomUUID().toString();
        var ended=send(owner,path+"/step",key,body,200);
        assertThat(ended.path("highlights").path("status").asString()).isEqualTo("FINAL");
        assertThat(ended.path("highlights").path("finalizedAtMonth").asInt()).isEqualTo(1201);
        assertThat(ended.path("highlights").path("decisionCount")).isEqualTo(current.path("highlights").path("decisionCount"));
        assertThat(ended.path("highlights").path("slides").size()).isBetween(1,12);
        assertThat(send(owner,path+"/step",key,body,200)).isEqualTo(ended);
        assertThat(load(owner,path)).isEqualTo(ended);
    }
    @Test void deathCauseIsSavedOnceAndTerminalSessionCannotAdvance() throws Exception {
        importer.importBundled(); var owner=guest();
        var born=send(owner,"/api/v1/sessions/generated/local",UUID.randomUUID().toString(),"{\"countryIso3\":\"JPN\"}",201);
        UUID id=UUID.fromString(born.path("id").asString()); String path="/api/v1/sessions/"+id;
        var original=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        LocalSimulation fixture=null;
        // Select a deterministic real-mortality fixture in the isolated DB, without a force-death production route.
        for(long seed=1;seed<=10000;seed++) {
            var candidate=new LocalSimulation(seed,original.engineVersion(),original.statisticsRelease(),original.request(),1200,
                    original.family(),original.skills(),original.flags(),original.lastEvents(),List.of(),null,original.birthFacts(),null);
            if(mortality.dies(candidate,"JPN",original.birthFacts().sex(),0,20,"character")){fixture=candidate;break;}
        }
        assertThat(fixture).isNotNull();
        var state=new LifeState(100,LifeState.Status.ACTIVE,new LifeState.Stats(20,50,60,50,50,0),null,
                json.treeToValue(born.path("state"),LifeState.class).history());
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb where id=?",json.writeValueAsString(fixture),json.writeValueAsString(state),id);
        String key=UUID.randomUUID().toString(); String body="{\"expectedVersion\":"+born.path("version").asLong()+"}";
        var ended=send(owner,path+"/step",key,body,200);
        var death=ended.path("simulation").path("death");
        assertThat(ended.path("state").path("status").asString()).isEqualTo("COMPLETE");
        assertThat(death.path("causeDetails").path("label").asString()).isNotBlank();
        assertThat(death.path("causeDetails").path("basis").asString()).isEqualTo("GAME_ASSUMPTION");
        assertThat(death.path("elapsedMonths").asInt()).isEqualTo(1201);
        assertThat(send(owner,path+"/step",key,body,200)).isEqualTo(ended);
        assertThat(load(owner,path)).isEqualTo(ended);
        send(owner,path+"/step",UUID.randomUUID().toString(),"{\"expectedVersion\":"+ended.path("version").asLong()+"}",409);
        assertThat(load(owner,path)).isEqualTo(ended);
        mvc.perform(get(path).cookie(guest())).andExpect(status().isNotFound());
        var saved=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        JsonNode persistedDeath=json.valueToTree(saved.death());
        assertThat(persistedDeath).isEqualTo(death);
        assertThat(ended.path("state").path("history").toString()).contains(death.path("causeDetails").path("label").asString());
    }
    @Test void cuttingContactThroughAnAnswerPersistsOnceAndDoesNotRemoveThePerson() throws Exception {
        importer.importBundled(); var owner=guest();
        var born=send(owner,"/api/v1/sessions/generated/local",UUID.randomUUID().toString(),"{\"countryIso3\":\"JPN\"}",201);
        UUID id=UUID.fromString(born.path("id").asString()); String path="/api/v1/sessions/"+id;
        var sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class);
        var people=new ArrayList<>(sim.family());
        people.add(new LocalSimulation.Person("contact-partner","partner","Партнёр",0,true,70,70,"FEMALE",null));
        sim=sim.evolve(360,people,sim.skills(),sim.flags(),sim.lastEvents(),List.of(),null,null);
        var state=json.treeToValue(born.path("state"),LifeState.class);
        var decision=new LifeState.Decision("test-cut","Трудный разговор",List.of(new LifeState.Option("cut_contact","Расстаться и прекратить общение")));
        state=new LifeState(30,LifeState.Status.ACTIVE,state.stats(),decision,state.history());
        var npc=new AdultState.Npc("contact-partner",new AdultState.Traits(50,50,50,50),"SECONDARY",null,0,75,"MARRIED","character","JP",List.of(),0,Set.of());
        var adult=new AdultState("adult-1",360,0,new AdultState.Traits(50,50,50,50),
                new AdultState.Education("SECONDARY",null,null,null,"COMPLETED"),
                new AdultState.Career("UNEMPLOYED",null,null,null,null,0,360,0,0,50,50,20,0,List.of()),
                new AdultState.Partnership("contact-partner","MARRIED",300,true,70,10,List.of()),Map.of(npc.id(),npc),List.of(),List.of(),List.of(),
                List.of(new AdultState.Story("contact-story","couple-conflict","CHOICE","ACTIVE",360,360,null,List.of(npc.id()),Map.of("cost:cut_contact","0"),List.of("CHOICE"))),
                List.of(),new AdultState.Pending("contact-story","CHOICE",List.of("cut_contact")),"SHARED_HOME",Set.of(),Map.of());
        // An actual pre-contact JSON snapshot: the new property is absent, rather than merely empty.
        ObjectNode legacy=(ObjectNode)json.valueToTree(adult); legacy.remove("contacts");
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb,adult_state=?::jsonb where id=?",json.writeValueAsString(sim),json.writeValueAsString(state),legacy.toString(),id);
        String route=path+"/decisions/test-cut/answers",key=UUID.randomUUID().toString();
        String body="{\"expectedVersion\":"+born.path("version").asLong()+",\"optionId\":\"cut_contact\"}";
        var result=send(owner,route,key,body,200);
        assertThat(send(owner,route,key,body,200)).isEqualTo(result);
        assertThat(load(owner,path)).isEqualTo(result);
        var partner=java.util.stream.StreamSupport.stream(result.path("simulation").path("family").spliterator(),false)
                .filter(p->p.path("id").asString().equals("contact-partner")).findFirst().orElseThrow();
        assertThat(partner.path("contact").path("status").asString()).isEqualTo("NO_CONTACT");
        assertThat(partner.path("alive").asBoolean()).isTrue();
        assertThat(result.path("simulation").path("adult").path("partnership").path("status").asString()).isEqualTo("DIVORCED");
        assertThat(result.path("simulation").path("elapsedMonths").asInt()).isEqualTo(360);
        var persisted=json.readValue(jdbc.queryForObject("select adult_state::text from life_session where id=?",String.class,id),AdultState.class);
        assertThat(persisted.contacts().get("contact-partner").status()).isEqualTo(ContactState.Status.NO_CONTACT);
        assertThat(persisted.memories().stream().filter(m->m.kind().equals("contact-ended"))).hasSize(1);
    }
    @Test void oldAdultSaveUpgradesLazilyAndCommandsRemainOwnedAtomicAndIdempotent() throws Exception {
        importer.importBundled();var owner=guest();var stranger=guest();
        var born=send(owner,"/api/v1/sessions/generated/local",UUID.randomUUID().toString(),"{\"countryIso3\":\"JPN\"}",201);
        String id=born.path("id").asString(),path="/api/v1/sessions/"+id;
        // Only a fixture in the isolated database: an existing pre-adult-1 save at age 18.
        var sim=json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,UUID.fromString(id)),LocalSimulation.class);
        sim=new LocalSimulation(5000012,sim.engineVersion(),sim.statisticsRelease(),sim.request(),216,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),List.of(),null,sim.birthFacts(),null);
        var state=new LifeState(18,LifeState.Status.ACTIVE,new LifeState.Stats(80,70,65,70,50,0),null,json.treeToValue(born.path("state"),LifeState.class).history());
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb,life_highlights=null where id=?",json.writeValueAsString(sim),json.writeValueAsString(state),UUID.fromString(id));
        assertThat(load(owner,path).path("simulation").path("adult").isNull()).isTrue();
        mvc.perform(get(path).cookie(stranger)).andExpect(status().isNotFound());
        var current=born;boolean sawAdultChoice=false;
        for(int i=0;i<55&& !current.path("state").path("status").asString().equals("COMPLETE");i++) {
            var decision=current.path("state").path("pendingDecision");boolean answering=decision.isObject();
            String route=answering?path+"/decisions/"+decision.path("id").asString()+"/answers":path+"/step";
            String body="{\"expectedVersion\":"+current.path("version").asLong()+(answering?",\"optionId\":\""+decision.path("options").get(0).path("id").asString()+"\"":"")+"}";
            String key=UUID.randomUUID().toString();current=send(owner,route,key,body,200);
            assertThat(send(owner,route,key,body,200)).isEqualTo(current);
            send(owner,route,UUID.randomUUID().toString(),body,409);
            assertThat(load(owner,path)).isEqualTo(current);
            assertThat(current.path("simulation").path("adult").path("modelVersion").asString()).isEqualTo("adult-1");
            assertThat(current.path("highlights").path("partialHistory").asBoolean()).isTrue();
            assertThat(current.path("highlights").path("trackedFromMonth").asInt()).isEqualTo(216);
            for(var person:current.path("simulation").path("family")) if(person.path("alive").asBoolean()) {
                assertThat(person.path("contact").path("status").asString()).isIn("IN_TOUCH","RARE_CONTACT","NO_CONTACT");
                assertThat(person.path("contact").path("trackedSinceMonth").asInt()).isGreaterThanOrEqualTo(216);
            }
            if(answering)sawAdultChoice=true;
            var finances=load(owner,path+"/finances");assertThat(finances.path("version").asLong()).isEqualTo(current.path("version").asLong());
            if(current.path("state").path("pendingDecision").isNull()&&sawAdultChoice&&i>20)break;
        }
        assertThat(sawAdultChoice).isTrue();
        assertThat(current.path("state").path("status").asString()).isEqualTo("ACTIVE");
        long version=current.path("version").asLong();String body="{\"expectedVersion\":"+version+"}";
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var requests=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->mvc.perform(post(path+"/step").cookie(owner).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus())).toList();
            assertThat(List.of(requests.get(0).get(),requests.get(1).get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(load(owner,path).path("version").asLong()).isEqualTo(version+1);
        assertThat(jdbc.queryForObject("select adult_state is not null from life_session where id=?",Boolean.class,UUID.fromString(id))).isTrue();
    }
    private MockCookie guest() throws Exception {return MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie"));}
    private JsonNode load(MockCookie owner,String path)throws Exception{return json.readTree(mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private JsonNode send(MockCookie owner,String path,String key,String body,int code)throws Exception{return json.readTree(mvc.perform(post(path).cookie(owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(code)).andReturn().getResponse().getContentAsString());}
}
