package com.life.life_backend;

import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.StatisticsImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
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
class CharacterCustomizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StatisticsImporter importer;
    @Autowired MortalityModel mortality;

    @Test void editsWholeBirthAtomicallyAndPreservesIdentityCreationKeyAndOriginalSetup() throws Exception {
        importer.importBundled();var owner=guest();String creationKey=key();var born=generate(owner,creationKey,"{}",201);
        String path=path(born);UUID id=UUID.fromString(born.path("id").asString());
        var originalSetup=jdbc.queryForObject("select setup::text from life_session where id=?",String.class,id);
        var originalSim=simulation(id);
        String motherId=born.path("simulation").path("family").get(0).path("id").asString();
        String changes="""
            {"name":" Новое имя ","sex":"FEMALE","birthYear":2024,
             "stats":{"health":90,"happiness":88,"intelligence":77,"relationships":66,"appearance":55,"aura":-12},
             "birthplace":{"countryCode":"JP","region":"Кансай","city":"Кобе","placeType":"URBAN"},
             "family":{"incomeQuintile":4,"housing":"Свой дом","religion":"Буддизм","language":"ja",
               "members":[{"id":"%s","role":"mother","name":"Анна","ageAtBirth":32,"occupation":"Врач","closeness":82,"trust":79},
                          {"role":"sister","name":"Анна","ageAtBirth":4,"closeness":71,"trust":68}]}}
            """.formatted(motherId);
        String editKey=key();var changed=edit(owner,path,editKey,1,changes,200);
        assertThat(changed.path("id")).isEqualTo(born.path("id"));assertThat(changed.path("version").asLong()).isEqualTo(2);
        var character=changed.path("character");var facts=character.path("birthFacts");
        assertThat(character.path("name").asString()).isEqualTo("Новое имя");assertThat(character.path("gender").asString()).isEqualTo("Женщина");
        assertThat(character.path("birthYear").asInt()).isEqualTo(2024);
        assertThat(character.path("birthplace").path("country").asString()).isEqualTo("Япония");
        assertThat(facts.path("countryIso3").asString()).isEqualTo("JPN");assertThat(facts.path("currency").asString()).isEqualTo("JPY");
        assertThat(facts.path("sex").asString()).isEqualTo("FEMALE");assertThat(facts.path("selectionProbability").path("value").asDouble()).isEqualTo(1);
        assertThat(facts.path("gdpPerCapita").path("basis").asString()).isEqualTo("STATISTIC");
        assertThat(facts.path("familyFaith").path("source").asString()).isEqualTo("user-customization");
        assertThat(character.path("background").path("father").isNull()).isTrue();assertThat(character.path("birthDetails").path("fatherPresent").asBoolean()).isFalse();
        assertThat(character.path("birthDetails").path("mother").path("age").asInt()).isEqualTo(32);
        assertThat(character.path("birthDetails").path("household").path("size").asInt()).isEqualTo(3);
        assertThat(changed.path("simulation").path("family")).hasSize(2);
        assertThat(changed.path("simulation").path("family").get(0).path("id").asString()).isEqualTo(motherId);
        assertThat(changed.path("simulation").path("family").get(1).path("id").asString()).startsWith("relative-");
        assertThat(changed.path("state").path("stats").path("relationships").asInt()).isEqualTo(66);
        assertThat(changed.path("state").path("stats").path("aura").asInt()).isEqualTo(-12);
        assertThat(changed.path("state").path("history")).hasSize(1);
        assertThat(changed.path("state").path("history").get(0).path("text").asString()).contains("Япония", "Кобе");
        assertThat(changed.path("simulation").path("elapsedMonths").asInt()).isZero();assertThat(changed.path("highlights").path("decisionCount").asInt()).isZero();
        assertThat(edit(owner,path,editKey,1,changes,200)).isEqualTo(changed);assertThat(load(owner,path)).isEqualTo(changed);
        assertThat(generate(owner,creationKey,"{}",200)).isEqualTo(changed);
        assertThat(jdbc.queryForObject("select setup::text from life_session where id=?",String.class,id)).isEqualTo(originalSetup);
        assertThat(simulation(id).seed()).isEqualTo(originalSim.seed());assertThat(simulation(id).request().birthYear()).isEqualTo(2024);
        assertThat(simulation(id).calibration().modelVersion()).isEqualTo(originalSim.calibration().modelVersion());
        assertThat(simulation(id).calibration().birthEconomy()).isEqualTo(originalSim.calibration().birthEconomy());
        assertThat(originalSim.naming()).isNotNull();
        assertThat(simulation(id).naming().people()).isEmpty(); // Custom strings are not split or reinterpreted as inherited surnames.
        assertThat(simulation(id).calibration().labour().values()).allMatch(l -> l.evidence().stream().allMatch(e -> e.year() <= 2024));
        var finances=load(owner,path+"/finances");assertThat(finances.path("location").path("countryCode").asString()).isEqualTo("JP");
        assertThat(finances.path("currency").asString()).isEqualTo("RUB");
        var second=edit(owner,path,key(),2,"{\"family\":{\"housing\":\"Квартира\"}}",200);
        assertThat(second.path("simulation").path("family")).isEqualTo(changed.path("simulation").path("family"));
        assertThat(second.path("character").path("birthFacts").path("familyFaith")).isEqualTo(facts.path("familyFaith"));
        assertThat(generate(owner,creationKey,"{}",200)).isEqualTo(second);
    }

    @Test void firstStepLocksBirthEvenAtAgeZeroButNameAndStatsRemainEditable() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner,key(),"{\"countryIso3\":\"JPN\"}",201);String path=path(born);
        safeFirstStep(UUID.fromString(born.path("id").asString()));
        var stepped=sendStep(owner,path,1);
        assertThat(stepped.path("state").path("age").asInt()).isZero();assertThat(stepped.path("state").path("status").asString()).isEqualTo("ACTIVE");
        for(String changes:List.of("{\"birthplace\":{\"city\":\"Токио\"}}","{\"sex\":\"MALE\"}","{\"birthYear\":2023}","{\"family\":{\"members\":[]}}"))
            assertThat(edit(owner,path,key(),2,changes,409).path("code").asString()).isEqualTo("BIRTH_CONFIGURATION_LOCKED");
        assertThat(load(owner,path)).isEqualTo(stepped);
        var changed=edit(owner,path,key(),2,"{\"name\":\"Другое имя\",\"stats\":{\"happiness\":45,\"relationships\":31,\"aura\":100}}",200);
        assertThat(changed.path("state").path("stats").path("happiness").asInt()).isEqualTo(45);
        assertThat(changed.path("state").path("stats").path("relationships").asInt()).isEqualTo(31);
        assertThat(changed.path("state").path("stats").path("aura").asInt()).isEqualTo(100);
        assertThat(changed.path("simulation").path("family")).isEqualTo(stepped.path("simulation").path("family"));
        assertThat(changed.path("state").path("history")).isEqualTo(stepped.path("state").path("history"));
    }

    @Test void invalidFamilyCountryAndUnknownFieldsCannotPartiallyChangeSave() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner,key(),"{}",201);String path=path(born);
        String member="{\"role\":\"mother\",\"name\":\"Мама\",\"ageAtBirth\":30}";
        for(String changes:List.of(
                "{\"name\":\"Не сохранить\",\"birthplace\":{\"countryCode\":\"ZZ\"}}",
                "{\"family\":{\"members\":["+member.replace("30","17")+"]}}",
                "{\"family\":{\"members\":["+member+","+member+","+member+"]}}",
                "{\"family\":{\"members\":["+member.replace("\"role\"","\"id\":\"foreign\",\"role\"")+"]}}",
                "{\"family\":{\"members\":["+member+",{\"role\":\"brother\",\"name\":\"Брат\",\"ageAtBirth\":25}]}}",
                "{\"family\":{\"incomeQuintile\":6}}", "{\"family\":{\"language\":\"   \"}}",
                "{\"family\":{\"unknown\":1}}", "{\"family\":{\"members\":[null]}}",
                "{\"stats\":{\"aura\":101}}", "{\"stats\":{\"happiness\":-1}}", "{\"birthYear\":2027}", "{\"birthplace\":{\"country\":\"Россия\"}}", "{}")) {
            edit(owner,path,key(),1,changes,400);assertThat(load(owner,path)).isEqualTo(born);
        }
        edit(guest(),path,key(),1,"{\"name\":\"Чужое\"}",404);
        edit(owner,path,key(),9,"{\"name\":\"Старая версия\"}",409);
    }

    @Test void emptyFamilyAndTwoMothersHaveConsistentBirthCardsAndCanContinue() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner,key(),"{}",201);String path=path(born);
        var empty=edit(owner,path,key(),1,"{\"family\":{\"members\":[]}}",200);
        assertThat(empty.path("simulation").path("family")).isEmpty();assertThat(empty.path("character").path("birthDetails").path("household").path("size").asInt()).isEqualTo(1);
        assertThat(empty.path("state").path("stats").path("relationships").asInt()).isZero();
        String member="{\"role\":\"mother\",\"name\":\"Анна\",\"ageAtBirth\":30}";
        var two=edit(owner,path,key(),2,"{\"family\":{\"members\":["+member+","+member+"]}}",200);
        assertThat(two.path("character").path("birthDetails").path("familyType").asString()).isEqualTo("TWO_MOTHERS");
        assertThat(two.path("simulation").path("family").get(0).path("id")).isNotEqualTo(two.path("simulation").path("family").get(1).path("id"));
        safeFirstStep(UUID.fromString(born.path("id").asString()));
        var next=sendStep(owner,path,3);assertThat(next.path("simulation").path("family")).hasSize(2);
        assertThat(next.path("state").path("status").asString()).isEqualTo("ACTIVE");
    }

    @Test void concurrentBirthChangesAcceptExactlyOneCommand() throws Exception {
        importer.importBundled();var owner=guest();var born=generate(owner,key(),"{}",201);String path=path(born);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=List.of("JP","IN").stream().map(country->pool.submit(()->mvc.perform(patch(path+"/character").cookie(owner)
                    .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":1,\"changes\":{\"birthplace\":{\"countryCode\":\""+country+"\"}}}"))
                    .andReturn().getResponse().getStatus())).toList();
            assertThat(List.of(tasks.get(0).get(),tasks.get(1).get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(load(owner,path).path("version").asLong()).isEqualTo(2);
    }

    private void safeFirstStep(UUID id) {
        var sim=simulation(id);long seed=1;
        for(;seed<1000;seed++) {
            var candidate=new LocalSimulation(seed,sim.engineVersion(),sim.statisticsRelease(),sim.request(),0,sim.family(),sim.skills(),sim.flags(),sim.lastEvents(),sim.scheduled(),null,sim.birthFacts(),null);
            if(!mortality.dies(candidate,sim.birthFacts().countryIso3(),sim.birthFacts().sex(),0,sim.month()==0?90:70,"character"))break;
        }
        jdbc.update("update life_session set simulation=jsonb_set(simulation,'{seed}',to_jsonb(?::bigint)),state=jsonb_set(state,'{stats,health}','90') where id=?",seed,id);
    }
    private LocalSimulation simulation(UUID id) { return json.readValue(jdbc.queryForObject("select simulation::text from life_session where id=?",String.class,id),LocalSimulation.class); }
    private String key() { return UUID.randomUUID().toString(); }
    private String path(JsonNode session) { return "/api/v1/sessions/"+session.path("id").asString(); }
    private MockCookie guest() throws Exception { return MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie")); }
    private JsonNode generate(MockCookie owner,String key,String body,int status) throws Exception { return json.readTree(mvc.perform(post("/api/v1/sessions/generated/local").cookie(owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString()); }
    private JsonNode edit(MockCookie owner,String path,String key,long version,String changes,int status) throws Exception { return json.readTree(mvc.perform(patch(path+"/character").cookie(owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+version+",\"changes\":"+changes+"}")).andExpect(status().is(status)).andReturn().getResponse().getContentAsString()); }
    private JsonNode load(MockCookie owner,String path) throws Exception { return json.readTree(mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private JsonNode sendStep(MockCookie owner,String path,long version) throws Exception { return json.readTree(mvc.perform(post(path+"/step").cookie(owner).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+version+"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
}
