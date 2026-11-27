package com.life.life_backend.engine.adult;

import com.life.life_backend.engine.*;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.engine.focus.*;
import com.life.life_backend.economy.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdultFocusTest {
    final ObjectMapper json=new ObjectMapper();
    final CountryCatalog countries=new CountryCatalog(json);
    final AdultLifeEngine engine;
    AdultFocusTest() throws Exception {
        var spending=mock(SpendingService.class);
        when(spending.shop(any(),eq("property"))).thenReturn(new FinanceView.Shop(1,30,FinanceView.Currency.RUB,null,"","","",java.math.BigDecimal.ZERO,null,List.of(),List.of()));
        engine=new AdultLifeEngine(new AdultStoryCatalog(json),new LocalSimulationEngine(new EventCatalog(json),mock(MortalityModel.class)),mock(CountryReference.class),countries,spending);
    }
    AdultWorld world(long seed,int month,String focus) {
        var sim=new LocalSimulation(seed,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),month,
                List.of(new LocalSimulation.Person("mother","mother","Мама",-300,true,60,60),new LocalSimulation.Person("friend","friend","Друг",-12,true,60,60)),
                Map.of(),Set.of(),Map.of(),List.of(),null);
        if(focus!=null)sim=sim.withFocus(FocusState.start(month).select(focus,month));
        return new AdultWorld(new LifeState(month/12,LifeState.Status.ACTIVE,new LifeState.Stats(70,70,50,60,50,0),null,List.of()),sim,
                new LocalBudget(month,20_000_000,List.of(),List.of()),null,new FinanceView.Location("RU","Россия",null),countries,1);
    }
    @Test void newEducationStoriesAreMoreLikelyWithoutIncreasingTheNumberOfQuestions() {
        int normal=0,learning=0,normalQuestions=0,learningQuestions=0;
        for(long seed=1;seed<=1500;seed++)for(String id:List.of("balanced","learning")){
            var w=world(seed,240,id);var next=engine.step(w.state,w.sim,w.snapshot(),w.budget,w.location);
            if(next.state().pendingDecision()!=null){
                if(id.equals("learning"))learningQuestions++;else normalQuestions++;
                var story=next.adult().stories().getLast();
                if(Set.of("university","retraining").contains(story.template())){if(id.equals("learning"))learning++;else normal++;}
            }
        }
        assertThat(learning).isGreaterThan((int)(normal*1.3));
        assertThat(learningQuestions).isEqualTo(normalQuestions);
    }
    @Test void familyAttentionMaintainsContactButDoesNotRestoreAnExplicitlyEndedRelationship() {
        var w=world(1,360,"friends");
        w.contacts.put("mother",new ContactState(ContactState.Status.NO_CONTACT,300,310,334,ContactState.Reason.PLAYER_CHOICE));
        w.contacts.put("friend",new ContactState(ContactState.Status.RARE_CONTACT,300,350,356,ContactState.Reason.TIME_APART));
        var next=engine.step(w.state,w.sim,w.snapshot(),w.budget,w.location);
        assertThat(next.adult().contacts().get("friend").status()).isEqualTo(ContactState.Status.IN_TOUCH);
        assertThat(next.adult().contacts().get("friend").lastContactMonth()).isEqualTo(362);
        assertThat(next.adult().contacts().get("mother").status()).isEqualTo(ContactState.Status.NO_CONTACT);
    }
    @Test void oldLivesKeepTheSameTransitionsAndWorkFocusEndsAtRetirement() {
        var legacy=world(10,360,null);var balanced=world(10,360,"balanced");
        var old=engine.step(legacy.state,legacy.sim,legacy.snapshot(),legacy.budget,legacy.location);
        var modern=engine.step(balanced.state,balanced.sim,balanced.snapshot(),balanced.budget,balanced.location);
        assertThat(modern.state()).isEqualTo(old.state());assertThat(modern.adult()).isEqualTo(old.adult());assertThat(modern.budget()).isEqualTo(old.budget());
        assertThat(old.simulation().focus()).isNull();
        var pension=world(5,778,"work");var retired=engine.step(pension.state,pension.sim,pension.snapshot(),pension.budget,pension.location);
        assertThat(retired.simulation().focus().id()).isEqualTo("balanced");
        assertThat(retired.adult().career().status()).isEqualTo("RETIRED");
        assertThat(YearFocus.view(retired.simulation(),retired.adult()).options().stream().filter(o->o.id().equals("work")).findFirst().orElseThrow().available()).isFalse();
    }
}
