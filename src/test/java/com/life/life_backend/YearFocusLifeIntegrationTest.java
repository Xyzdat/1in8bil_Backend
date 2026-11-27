package com.life.life_backend;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.adult.*;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.engine.focus.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class YearFocusLifeIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired CharacterGenerator generator;
    @Autowired CountryCatalog countries;
    @Autowired LocalSimulationEngine childhood;
    @Autowired AdultLifeEngine adultEngine;
    @Autowired ObjectMapper json;

    @Test void fullLivesWithChangingFocusRemainRestorableAndEndAcrossAllCountries() throws Exception {
        importer.importBundled();int index=0;var used=new HashSet<String>();
        for(String country:countries.codes()){
            var born=generator.generate(9_000_000L+index,new LocalSimulation.BirthRequest(country,2026));
            var state=born.state();var sim=born.simulation();AdultState adult=null;var budget=LocalBudget.atMonth(null,0);
            var location=new FinanceView.Location(born.character().birthplace().countryCode(),born.character().birthplace().country(),null);
            int selectedYear=-1,iterations=0;
            while(state.status()==LifeState.Status.ACTIVE&&iterations++<1300){
                int age=sim.month()/12;
                if(age>=3&&selectedYear!=age){
                    var options=FocusCatalog.all().stream().filter(option->option.allowed(age)).toList();
                    var choice=options.get(Math.floorMod(index+age,options.size()));
                    sim=sim.withFocus(sim.focus().select(choice.id(),sim.month()));selectedYear=age;used.add(choice.id());
                }
                if(sim.month()<216||(state.pendingDecision()!=null&&adult==null)){
                    var result=state.pendingDecision()==null?childhood.step(state,sim):childhood.answer(state,sim,state.pendingDecision().id(),state.pendingDecision().options().getFirst().id());
                    state=result.state();sim=result.simulation();budget=LocalBudget.atMonth(budget,sim.month());
                }else{
                    AdultLifeEngine.Transition result;
                    if(state.pendingDecision()==null)result=adultEngine.step(state,sim,adult,budget,location);
                    else {
                        var question=state.pendingDecision();
                        try{result=adultEngine.answer(state,sim,adult,budget,location,question.id(),question.options().getFirst().id());}
                        catch(com.life.life_backend.web.ApiFailure failure){
                            if(!failure.getMessage().contains("средств"))throw failure;
                            result=adultEngine.answer(state,sim,adult,budget,location,question.id(),question.options().getLast().id());
                        }
                    }
                    if(sim.month()%120==0&&state.pendingDecision()==null){
                        var restored=json.readValue(json.writeValueAsString(sim),LocalSimulation.class);
                        assertThat(restored).isEqualTo(sim);
                        assertThat(adultEngine.step(state,restored,adult,budget,location)).isEqualTo(result);
                    }
                    state=result.state();sim=result.simulation();adult=result.adult();budget=result.budget();
                }
                assertThat(sim.focus()).isNotNull();
                assertThat(sim.focus().settledMonth()).isLessThanOrEqualTo(sim.month());
                assertThat(state.stats().health()).isBetween(0,100);
                assertThat(state.stats().intelligence()).isBetween(0,100);
            }
            assertThat(state.status()).as(country).isEqualTo(LifeState.Status.COMPLETE);
            assertThat(sim.view(adult).yearlyFocus().canChange()).isFalse();
            assertThat(budget.balance()).isEqualTo(budget.entries().stream().mapToLong(entry->entry.income()-entry.expense()).sum());
            var byYear=state.history().stream().filter(event->event.link()!=null&&event.link().phase().equals("OFFER"))
                    .collect(java.util.stream.Collectors.groupingBy(LifeState.HistoryEvent::age,java.util.stream.Collectors.counting()));
            assertThat(byYear.values()).allMatch(count->count<=2);
            assertThat(json.readValue(json.writeValueAsString(sim),LocalSimulation.class)).isEqualTo(sim);
            index++;
        }
        assertThat(used).containsExactlyInAnyOrderElementsOf(FocusCatalog.all().stream().map(FocusCatalog.Definition::id).toList());
        System.out.println("Focused full-life audit: "+index+"/36, changing priority annually");
    }
}
