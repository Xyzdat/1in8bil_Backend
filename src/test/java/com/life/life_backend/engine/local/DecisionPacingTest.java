package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.highlights.EventLink;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionPacingTest {
    LocalSimulation sim(int month) {
        return new LocalSimulation(123,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),month,
                List.of(),Map.of(),Set.of(),Map.of(),List.of(),null);
    }
    LifeState state(int health,List<LifeState.HistoryEvent> history) { return new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(health,80,50,70,50,0),null,history); }
    LifeState.HistoryEvent event(String decision,int month,String phase) {
        return new LifeState.HistoryEvent(decision+phase,DecisionPacing.year(month),"test","test",null,
                new EventLink(decision,"test","SOCIAL",phase,month,decision,null,null,null,List.of(),List.of(),List.of()));
    }
    @Test void offersNotAnswersCountOnceAndSecondQuestionInSameStoryUsesBudget() {
        var state=state(80,List.of(event("story-CHOICE",362,"OFFER"),event("story-CHOICE",362,"DECISION"),event("story-COHABIT_CHOICE",366,"OFFER")));
        assertThat(DecisionPacing.count(state,sim(366),370)).isEqualTo(2);
        assertThat(DecisionPacing.allowed(state,sim(366),372)).isFalse();
        assertThat(DecisionPacing.allowed(state,sim(372),374)).isTrue();
    }
    @Test void persistedBudgetAndSpacingSurviveReloadEvenForOldHistoryWithoutLinks() {
        var old=state(80,List.of(new LifeState.HistoryEvent("local-362-help-answer",30,"answer","old")));
        var recorded=DecisionPacing.recordOffer(old,sim(366),366);
        assertThat(DecisionPacing.count(old,recorded,370)).isEqualTo(2);
        var json=new ObjectMapper();var restored=json.readValue(json.writeValueAsString(recorded),LocalSimulation.class);
        assertThat(DecisionPacing.allowed(state(80,List.of()),restored,370)).isFalse();
        var once=DecisionPacing.recordOffer(state(80,List.of()),sim(362),362);
        assertThat(DecisionPacing.allowed(state(80,List.of()),once,364)).isFalse();
        assertThat(DecisionPacing.allowed(state(80,List.of()),once,366)).isTrue();
    }
    @Test void zeroHealthCompletesChildImmediatelyWithNoMortalityDrawOrPendingAnswer() throws Exception {
        var mortality=mock(MortalityModel.class);var engine=new LocalSimulationEngine(new EventCatalog(new ObjectMapper()),mortality);
        var s=state(0,List.of());s=new LifeState(4,s.status(),s.stats(),new LifeState.Decision("old","old",List.of()),s.history());
        var ended=engine.step(s,sim(50));
        assertThat(ended.state().status()).isEqualTo(LifeState.Status.COMPLETE);assertThat(ended.simulation().month()).isEqualTo(50);
        assertThat(ended.state().pendingDecision()).isNull();assertThat(ended.state().history()).hasSize(1);verifyNoInteractions(mortality);
        assertThat(engine.completeIfHealthDepleted(ended.state(),ended.simulation(),DeathCauseModel.Context.EMPTY)).isEqualTo(ended);
    }
    @Test void zeroHealthNeverNeedsCountryMortalityTable() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        assertThat(new MortalityModel(jdbc).dies(sim(360),"RUS","MALE",0,0,"character")).isTrue();verifyNoInteractions(jdbc);
    }
}
