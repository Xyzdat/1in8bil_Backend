package com.life.life_backend;

import com.life.life_backend.economy.LocalBudget;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
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
class BudgetManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StatisticsImporter importer;
    @MockitoBean MortalityModel mortality;
    record Game(Cookie owner,UUID id){String path(){return "/api/v1/sessions/"+id;}}
    private Game game(long seed) throws Exception {
        importer.importBundled();var owner=guest();
        var born=json.readTree(mvc.perform(post("/api/v1/sessions/generated/local").cookie(owner).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countryIso3\":\"RUS\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var id=UUID.fromString(born.path("id").asString());
        var sim=new LocalSimulation(seed,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),360,List.of(),Map.of(),Set.of(),Map.of(),List.of(),null);
        var state=new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(80,70,60,65,50,0),null,List.of());
        jdbc.update("update life_session set simulation=?::jsonb,state=?::jsonb,local_budget=?::jsonb,adult_state=null,life_highlights=null where id=?",
                json.writeValueAsString(sim),json.writeValueAsString(state),json.writeValueAsString(new LocalBudget(360,20_000_000,List.of(),List.of())),id);
        return new Game(owner,id);
    }
    private Cookie guest() throws Exception{return MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie"));}
    private String key(){return UUID.randomUUID().toString();}
    private JsonNode act(Game game,String token,long version,String action,String option,int expected) throws Exception{
        return json.readTree(mvc.perform(post(game.path()+"/budget/actions").cookie(game.owner()).header("Idempotency-Key",token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":"+version+",\"action\":\""+action+"\",\"optionId\":\""+option+"\"}"))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode read(Game game,String suffix) throws Exception{return json.readTree(mvc.perform(get(game.path()+suffix).cookie(game.owner())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private String row(Game game){return jdbc.queryForObject("select row_to_json(s)::text from life_session s where id=?",String.class,game.id());}

    @Test void sectionsAreOwnedPureAndStrategiesAreVersionedAndIdempotent() throws Exception {
        var game=game(47);String before=row(game);
        for(String section:List.of("income","expenses","jobs","side-jobs","businesses","strategies","assets")){
            var page=read(game,"/budget/"+section);assertThat(page.path("available").asBoolean()).isTrue();assertThat(page.path("version").asLong()).isEqualTo(1);assertThat(page.path("currency").asString()).isEqualTo("RUB");
        }
        assertThat(row(game)).isEqualTo(before);
        mvc.perform(get(game.path()+"/budget/income")).andExpect(status().isUnauthorized());
        mvc.perform(get(game.path()+"/budget/income").cookie(guest())).andExpect(status().isNotFound());
        String token=key();var result=act(game,token,1,"SET_STRATEGY","cash",200);
        assertThat(result.path("version").asLong()).isEqualTo(2);assertThat(result.path("budgetAction").path("status").asString()).isEqualTo("APPLIED");
        String after=row(game);assertThat(act(game,token,1,"SET_STRATEGY","cash",200)).isEqualTo(result);
        assertThat(row(game)).isEqualTo(after);
        act(game,token,1,"SET_STRATEGY","deposit",409);act(game,key(),1,"SET_STRATEGY","deposit",409);
        assertThat(read(game,"/budget/strategies").path("data").path("selectedId").asString()).isEqualTo("cash");
        assertThat(read(game,"").path("budgetAction").isNull()).isTrue();
        assertThat(read(game,"/finances").path("summary").path("depositRateBasisPoints").asInt()).isZero();
    }
    @Test void declinedHiringIsStoredWithoutChangingLifeAndCannotBeRedrawn() throws Exception {
        Game declined=null;JsonNode first=null;String token=null;
        for(long seed=1;seed<=10&&declined==null;seed++){
            var game=game(seed);String before=row(game);String request=key();var result=act(game,request,1,"APPLY_JOB","warehouse",200);
            if(result.path("budgetAction").path("status").asString().equals("DECLINED")){
                declined=game;first=result;token=request;assertThat(row(game)).isEqualTo(before);
                assertThat(result.path("version").asLong()).isEqualTo(1);
            }
        }
        assertThat(declined).isNotNull();
        assertThat(act(declined,token,1,"APPLY_JOB","warehouse",200)).isEqualTo(first);
        var retry=act(declined,key(),1,"APPLY_JOB","warehouse",200);
        assertThat(retry.path("budgetAction")).isEqualTo(first.path("budgetAction"));
        assertThat(jdbc.queryForObject("select count(*) from life_session_action where session_id=? and budget_result->>'status'='DECLINED'",Integer.class,declined.id())).isEqualTo(2);
    }
    @Test void secondaryPersistsAndNoMoneyIsAccruedByRecoveryOrAnswers() throws Exception {
        var game=game(47);var side=act(game,key(),1,"TAKE_SIDE_JOB","weekend-help",200);
        assertThat(side.path("version").asLong()).isEqualTo(2);
        var income=read(game,"/budget/income");String id=income.path("data").path("secondary").path("id").asString();
        String before=row(game);read(game,"");read(game,"/finances");read(game,"/budget/income");assertThat(row(game)).isEqualTo(before);
        var saved=json.readTree(jdbc.queryForObject("select adult_state::text from life_session where id=?",String.class,game.id()));
        assertThat(saved.path("budgetManagement").path("secondary").path("id").asString()).isEqualTo(id);
        assertThat(read(game,"/finances").path("summary").path("availableBalance").decimalValue()).isEqualByComparingTo("200000.00");
        var quit=act(game,key(),2,"QUIT_SIDE_JOB",id,200);assertThat(quit.path("version").asLong()).isEqualTo(3);
        assertThat(read(game,"/budget/income").path("data").path("secondary").isNull()).isTrue();
    }
    @Test void businessStartIsAtomicAndConcurrentCommandsHaveOneWinner() throws Exception {
        var game=game(47);String request=key();var opened=act(game,request,1,"OPEN_BUSINESS","workshop",200);
        var after=read(game,"/finances").path("summary").path("availableBalance");
        assertThat(after.decimalValue()).isLessThan(new java.math.BigDecimal("200000.00"));
        assertThat(act(game,request,1,"OPEN_BUSINESS","workshop",200)).isEqualTo(opened);
        String saved=row(game);act(game,key(),2,"OPEN_BUSINESS","workshop",409);assertThat(row(game)).isEqualTo(saved);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->commandStatus(game,2,"cash"));var b=pool.submit(()->commandStatus(game,2,"deposit"));
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
    }
    private int commandStatus(Game game,long version,String option) throws Exception{
        return mvc.perform(post(game.path()+"/budget/actions").cookie(game.owner()).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":"+version+",\"action\":\"SET_STRATEGY\",\"optionId\":\""+option+"\"}")).andReturn().getResponse().getStatus();
    }
    @Test void saleIsVersionedPersistentAndCannotBeRepeated() throws Exception {
        var game=game(47);var p=new LocalBudget.Purchase(UUID.randomUUID(),360,"property","motorcycle","Транспорт",null,2_000_000,12000,null,null,null);
        var budget=new LocalBudget(360,20_000_000,List.of(),List.of()).book(p);
        jdbc.update("update life_session set local_budget=?::jsonb where id=?",json.writeValueAsString(budget),game.id());
        String asset=read(game,"/budget/assets").path("data").path("assets").get(0).path("id").asString();
        String original=row(game);var preview=read(game,"/budget/assets/"+asset+"/sale-preview");assertThat(row(game)).isEqualTo(original);
        assertThat(preview.path("version").asLong()).isEqualTo(1);String token=key();
        var sale=act(game,token,1,"SELL_ASSET",asset,200);
        assertThat(read(game,"/budget/assets").path("data").path("assets").size()).isZero();
        assertThat(read(game,"/finances").path("purchases").get(0).path("status").asString()).isEqualTo("SOLD");
        assertThat(act(game,token,1,"SELL_ASSET",asset,200)).isEqualTo(sale);
        String after=row(game);act(game,key(),2,"SELL_ASSET",asset,409);assertThat(row(game)).isEqualTo(after);
    }
    @Test void completePendingAndExternalAreReadOnlyAndFailuresArePure() throws Exception {
        var game=game(47);String before=row(game);act(game,key(),1,"SET_STRATEGY","trading",409);assertThat(row(game)).isEqualTo(before);
        jdbc.update("update life_session set state=jsonb_set(state,'{pendingDecision}',?::jsonb) where id=?","{\"id\":\"d\",\"title\":\"Выбор\",\"options\":[]}",game.id());
        before=row(game);act(game,key(),1,"SET_STRATEGY","cash",409);assertThat(row(game)).isEqualTo(before);
        jdbc.update("update life_session set state=jsonb_set(jsonb_set(state,'{pendingDecision}','null'::jsonb),'{status}','\"COMPLETE\"'::jsonb) where id=?",game.id());
        before=row(game);act(game,key(),1,"SET_STRATEGY","cash",409);assertThat(row(game)).isEqualTo(before);
        assertThat(read(game,"/budget/strategies").path("data").path("options").get(0).path("available").asBoolean()).isFalse();
        jdbc.update("update life_session set engine_id='1in8b' where id=?",game.id());
        before=row(game);assertThat(read(game,"/budget/income").path("available").asBoolean()).isFalse();
        act(game,key(),1,"SET_STRATEGY","cash",409);assertThat(row(game)).isEqualTo(before);
        jdbc.update("update life_session set engine_id='local',simulation=null,adult_state=null where id=?",game.id());
        assertThat(read(game,"/budget/income").path("available").asBoolean()).isFalse();
    }
    @Test void elapsedStepAccruesBusinessOnceAndRecoveredDetailsMatchSummary() throws Exception {
        var game=game(47);act(game,key(),1,"OPEN_BUSINESS","workshop",200);String token=key();
        var request=post(game.path()+"/step").cookie(game.owner()).header("Idempotency-Key",token).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}");
        mvc.perform(request).andExpect(status().isOk());
        String before=row(game);
        mvc.perform(post(game.path()+"/step").cookie(game.owner()).header("Idempotency-Key",token).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        assertThat(row(game)).isEqualTo(before);
        var finances=read(game,"/finances");var income=read(game,"/budget/income").path("data");var expenses=read(game,"/budget/expenses").path("data");
        assertThat(income.path("total").decimalValue()).isEqualByComparingTo(finances.path("summary").path("income").decimalValue());
        assertThat(expenses.path("total").decimalValue()).isEqualByComparingTo(finances.path("summary").path("expenses").decimalValue());
        assertThat(income.path("business").path("revenue").decimalValue()).isPositive();
        assertThat(income.path("business").path("costs").decimalValue()).isPositive();
        long rows=jdbc.queryForObject("select count(*) from life_session s,jsonb_array_elements(s.local_budget->'entries') e where s.id=? and e->>'sourceId'='BUSINESS_REVENUE'",Long.class,game.id());
        assertThat(rows).isEqualTo(2);assertThat(row(game)).isEqualTo(before);
    }
}
