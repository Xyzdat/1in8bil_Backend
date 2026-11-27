package com.life.life_backend.engine.focus;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class YearFocusTest {
    final ObjectMapper json=new ObjectMapper();
    LifeState state(){return new LifeState(3,LifeState.Status.ACTIVE,new LifeState.Stats(70,70,50,60,50,0),null,List.of());}
    LocalSimulation sim(long seed,int month,String id){
        return new LocalSimulation(seed,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),month,
                List.of(new LocalSimulation.Person("mother","mother","Мама",-300,true,60,60)),Map.of(),Set.of(),Map.of(),List.of(),null)
                .withFocus(FocusState.start(month).select(id,month));
    }
    @Test void annualEffectsAccrueByElapsedTimeWithSignedRemaindersAndNoSelectionBonus() {
        var simulation=sim(42,36,"learning");var current=state();
        for(int month=38;month<=48;month+=2){
            var result=FocusEffects.settle(current,simulation,null,month);
            current=result.state();simulation=result.simulation().evolve(month,result.simulation().family(),result.simulation().skills(),Set.of(),Map.of(),List.of(),null,null);
        }
        assertThat(current.stats().intelligence()).isEqualTo(53);
        assertThat(current.stats().happiness()).isEqualTo(69);
        assertThat(simulation.skills()).containsEntry("learning",3);
        assertThat(simulation.focus().id()).isEqualTo("learning"); // Choice persists across the year boundary.
        assertThat(FocusEffects.settle(current,simulation,null,48).state()).isEqualTo(current);
        var selected=simulation.focus();
        for(int i=0;i<100;i++)selected=selected.select(i%2==0?"home":"health",48);
        assertThat(selected.remainders()).isEmpty();assertThat(selected.changes()).hasSize(2);
        assertThat(FocusEffects.settle(current,simulation.withFocus(selected),null,48).state()).isEqualTo(current);
    }
    @Test void switchingMidyearOnlyAppliesTheNewFocusToFutureMonthsAndKeepsFractions() {
        var simulation=sim(1,36,"learning");
        var first=FocusEffects.settle(state(),simulation,null,42);
        var middle=first.simulation().evolve(42,first.simulation().family(),first.simulation().skills(),Set.of(),Map.of(),List.of(),null,null);
        middle=middle.withFocus(middle.focus().select("rest",42));
        var result=FocusEffects.settle(first.state(),middle,null,48);
        assertThat(result.state().stats().intelligence()).isEqualTo(51); // +1.5 - 0.5 = +1, no discarded fractions.
        assertThat(result.state().stats().health()).isEqualTo(71);
        assertThat(result.state().stats().happiness()).isEqualTo(70);
        assertThat(result.simulation().skills()).containsEntry("learning",1);
    }
    @Test void familyEffectsUseStableIdsClampScoresAndPreserveNamesTrustAndDeath() {
        var simulation=sim(1,36,"home");
        var dead=new LocalSimulation.Person("father","father","Папа",-300,false,50,50);
        simulation=simulation.evolve(36,List.of(simulation.family().getFirst(),dead),Map.of(),Set.of(),Map.of(),List.of(),null,null);
        var result=FocusEffects.settle(state(),simulation,null,48);
        assertThat(result.simulation().family().getFirst().closeness()).isEqualTo(62);
        assertThat(result.simulation().family().getFirst().trust()).isEqualTo(60);
        assertThat(result.simulation().family().get(1)).isEqualTo(dead);
        assertThat(result.state().stats().relationships()).isEqualTo(62);
        assertThat(result.simulation().focus().lastApplied().closeness()).containsOnlyKeys("mother");
    }
    @Test void restorationPreservesAccrualAndMissingFieldKeepsOldRules() {
        var simulation=sim(1,36,"learning");
        var result=FocusEffects.settle(state(),simulation,null,38);
        var restored=json.readValue(json.writeValueAsString(result.simulation()),LocalSimulation.class);
        assertThat(restored).isEqualTo(result.simulation());
        assertThat(FocusEffects.settle(result.state(),restored,null,40))
                .isEqualTo(FocusEffects.settle(result.state(),result.simulation(),null,40));
        var old=(tools.jackson.databind.node.ObjectNode)json.valueToTree(simulation);old.remove("focus");
        var legacy=json.treeToValue(old,LocalSimulation.class);
        assertThat(YearFocus.view(legacy,null)).isNull();
        assertThat(FocusEffects.settle(state(),legacy,null,48).simulation()).isEqualTo(legacy);
        assertThat(YearFocus.childhoodWeight(legacy,"puzzle",5)).isEqualTo(5);
    }
    @Test void catalogReferencesRealChildhoodEventsAndWeightsEncourageChoiceChainsWithoutGuaranteeingThem() throws Exception {
        var events=new EventCatalog(json);var engine=new LocalSimulationEngine(events,mock(MortalityModel.class));
        for(var option:FocusCatalog.all())for(var id:option.events())assertThat(events.get(id)).isNotNull();
        int plain=0,creative=0,plainQuestions=0,creativeQuestions=0;
        for(long seed=1;seed<=2000;seed++){
            var baseline=engine.step(state(),sim(seed,36,"balanced"));
            var focused=engine.step(state(),sim(seed,36,"creativity"));
            if("drawing_choice".equals(baseline.simulation().pendingTemplate()))plain++;
            if("drawing_choice".equals(focused.simulation().pendingTemplate()))creative++;
            if(baseline.state().pendingDecision()!=null)plainQuestions++;
            if(focused.state().pendingDecision()!=null)creativeQuestions++;
        }
        assertThat(creative).isGreaterThan((int)(plain*1.3));
        assertThat(creativeQuestions).isEqualTo(plainQuestions);
        assertThat(creative).isLessThan(creativeQuestions);
    }
}
