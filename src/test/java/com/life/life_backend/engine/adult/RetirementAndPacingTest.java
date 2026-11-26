package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.life.life_backend.engine.adult.AdultState.*;

class RetirementAndPacingTest {
    final ObjectMapper json = new ObjectMapper();
    AdultWorld world(int month, int health) throws Exception {
        var sim = new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),month,
                List.of(),Map.of(),Set.of(),Map.of(),List.of(),null);
        var state = new LifeState(month/12,LifeState.Status.ACTIVE,new LifeState.Stats(health,80,65,70,50,0),null,List.of());
        var w = new AdultWorld(state,sim,new LocalBudget(month,10_000_000,List.of(),List.of()),null,
                new FinanceView.Location("RU","Россия",null),new CountryCatalog(json),1);
        w.career = new Career("EMPLOYED","Мастер","Услуги","Компания","Специалист",2_000_000,300,480,2,80,80,20,0,
                List.of(new Employment("job-1","Мастер","Компания",300,null,null)));
        return w;
    }
    AdultLifeEngine engine() throws Exception {
        var spending = mock(SpendingService.class);
        when(spending.shop(any(),eq("property"))).thenReturn(new FinanceView.Shop(1,30,FinanceView.Currency.RUB,null,"","","",java.math.BigDecimal.ZERO,null,List.of(),List.of()));
        return new AdultLifeEngine(new AdultStoryCatalog(json),new LocalSimulationEngine(new EventCatalog(json),mock(MortalityModel.class)),
                mock(CountryReference.class),new CountryCatalog(json),spending);
    }
    AdultLifeEngine.Transition step(AdultWorld w) throws Exception { return engine().step(w.state,w.sim,w.snapshot(),w.budget,w.location); }
    Story story(String id,String template,String stage,int began,int due,Map<String,String> vars) {
        return new Story(id,template,stage,"ACTIVE",began,due,null,List.of(),vars,List.of("CHOICE"));
    }

    @Test void pensionStartsOnceAt65AndOld67YearOldCannotTakeJobOrBusiness() throws Exception {
        var w=world(778,80);var first=step(w);
        assertThat(first.adult().career().status()).isEqualTo("RETIRED");
        assertThat(first.adult().career().history().getFirst().endedMonth()).isEqualTo(780);
        assertThat(first.budget().entries()).anyMatch(e->e.id().equals("salary-780"));
        var next=engine().step(first.state(),first.simulation(),first.adult(),first.budget(),w.location);
        assertThat(next.budget().entries()).anyMatch(e->e.id().equals("pension-781"));
        assertThat(next.budget().entries()).noneMatch(e->e.id().equals("salary-781"));
        assertThat(next.state().history().stream().filter(e->e.title().equals("Выход на пенсию"))).hasSize(1);
        assertThat(next.adult().career().monthlyPension()).isEqualTo(first.adult().career().monthlyPension());

        var legacy=world(804,80);RetirementPolicy.apply(legacy,new AdultStoryCatalog(json));
        assertThat(AdultConditions.test("job",legacy)).isFalse();assertThat(AdultConditions.test("business",legacy)).isFalse();
        var progress=new StoryProgress(story("x","employment","CHOICE",804,804,Map.of()));
        for(String action:List.of("apply_job","apply_part_time","job_referral","business")) assertThat(new CareerSystem().act(action,legacy,progress).proceed()).isFalse();
        assertThat(legacy.career.history()).hasSize(1);assertThat(legacy.career.status()).isEqualTo("RETIRED");
    }

    @Test void obsoletePaidWorkChoiceClosesWithoutDebitOrFakeDecision() throws Exception {
        var w=world(804,80);CareerSystem.leave(w,"old");
        w.stories.add(story("old-offer","business","CHOICE",802,802,Map.of("cost:start","2000000")));
        // Use the actual catalog ID and cost so this exercises the public answer path.
        var definition=new AdultStoryCatalog(json).all().stream().filter(d->d.condition().equals("business")).findFirst().orElseThrow();
        w.stories.clear();var choice=definition.choices().getFirst();
        w.stories.add(story("old-offer",definition.id(),"CHOICE",802,802,Map.of("cost:"+choice.id(),"2000000")));
        w.pending=new Pending("old-offer","CHOICE",List.of(choice.id()));
        w.decision(new LifeState.Decision("old-offer-CHOICE","old",List.of(new LifeState.Option(choice.id(),choice.label()))));
        var result=engine().answer(w.state,w.sim,w.snapshot(),w.budget,w.location,"old-offer-CHOICE",choice.id());
        assertThat(result.budget()).isEqualTo(w.budget);assertThat(result.state().pendingDecision()).isNull();
        assertThat(result.adult().stories().getFirst().status()).isEqualTo("CANCELLED");
        assertThat(result.state().history()).noneMatch(e->e.link()!=null&&e.link().phase().equals("DECISION"));
    }

    @Test void zeroHealthOverridesPendingQuestionAndCannotBeHealedByDailyEvent() throws Exception {
        var w=world(360,0);w.pending=new Pending("x","CHOICE",List.of("rest"));
        w.decision(new LifeState.Decision("x","rest?",List.of(new LifeState.Option("rest","Rest"))));
        var ended=step(w);
        assertThat(ended.state().status()).isEqualTo(LifeState.Status.COMPLETE);
        assertThat(ended.state().stats().health()).isZero();assertThat(ended.simulation().month()).isEqualTo(360);
        assertThat(ended.state().pendingDecision()).isNull();assertThat(ended.budget()).isEqualTo(w.budget);
        assertThat(ended.adult().career().history().getFirst().endedMonth()).isEqualTo(360);
        assertThat(ended.simulation().death().causeDetails().label()).isEqualTo("Критическое ухудшение здоровья");
    }

    @Test void choiceAndAnnualDiseaseBurdenThatReachZeroEndInSameTransition() throws Exception {
        var w=world(360,4);var d=new AdultStoryCatalog(json).all().stream().filter(x->x.condition().equals("burnout")).findFirst().orElseThrow();
        var choice=d.choices().stream().filter(c->c.action().equals("work_harder")).findFirst().orElseThrow();
        w.stories.add(story("burn","burnout","CHOICE",360,360,Map.of()));
        w.pending=new Pending("burn","CHOICE",List.of(choice.id()));w.decision(new LifeState.Decision("burn-CHOICE","burnout",List.of(new LifeState.Option(choice.id(),choice.label()))));
        var ended=engine().answer(w.state,w.sim,w.snapshot(),w.budget,w.location,"burn-CHOICE",choice.id());
        assertThat(ended.state().status()).isEqualTo(LifeState.Status.COMPLETE);assertThat(ended.simulation().month()).isEqualTo(360);
        assertThat(ended.state().history()).extracting(LifeState.HistoryEvent::title).containsExactly("Твоё решение: burnout","Жизнь завершилась");
        var sick=world(358,1);sick.diseases.add(new Disease("ill","HEALTH_CONDITION",350,60,"ACTIVE","NONE",370,null,false,90));
        var terminal=step(sick);assertThat(terminal.state().status()).isEqualTo(LifeState.Status.COMPLETE);
        assertThat(terminal.simulation().death().causeDetails().conditionId()).isEqualTo("ill");
        assertThat(terminal.state().stats().health()).isZero();assertThat(terminal.simulation().month()).isEqualTo(360);
    }

    @Test void followUpQuestionWaitsForBudgetButAutomaticOutcomeDoesNot() throws Exception {
        var w=world(368,80);var romance=new AdultStoryCatalog(json).all().stream().filter(d->"cohabit".equals(d.laterAction())||"cohabit".equals(d.reviewAction())).findFirst().orElseThrow();
        String phase="cohabit".equals(romance.reviewAction())?"REVIEW":"LATER";
        w.stories.add(story("romance",romance.id(),phase,350,370,Map.of()));
        w.stories.add(new Story("one","employment","CHOICE","COMPLETED",362,362,362,List.of(),Map.of(),List.of()));
        w.stories.add(new Story("two","employment","CHOICE","COMPLETED",366,366,366,List.of(),Map.of(),List.of()));
        w.stories.add(story("qualification","retraining","REVIEW",350,370,Map.of()));
        var result=step(w);
        assertThat(result.state().pendingDecision()).isNull();
        assertThat(result.adult().stories().stream().filter(s->s.id().equals("romance")).findFirst().orElseThrow().dueMonth()).isEqualTo(372);
        assertThat(result.adult().career().qualification()).isEqualTo(3);
    }

    @Test void calmLivesActuallyReceiveFewerQuestionsThanTroubledLives() throws Exception {
        var calm=world(360,80);var troubled=world(360,30);CareerSystem.leave(troubled,"test");
        troubled.budget=new LocalBudget(360,-1000,List.of(),List.of());
        var engine=engine();int calmQuestions=0,troubledQuestions=0;
        for(int seed=0;seed<200;seed++) {
            var old=calm.sim;
            var sim=new LocalSimulation(seed,old.engineVersion(),old.statisticsRelease(),old.request(),old.month(),old.family(),old.skills(),old.flags(),old.lastEvents(),List.of(),null);
            if(engine.step(calm.state,sim,calm.snapshot(),calm.budget,calm.location).state().pendingDecision()!=null)calmQuestions++;
            if(engine.step(troubled.state,sim,troubled.snapshot(),troubled.budget,troubled.location).state().pendingDecision()!=null)troubledQuestions++;
        }
        assertThat(calmQuestions).isBetween(1,30);assertThat(troubledQuestions).isGreaterThan(calmQuestions*4);
    }
    @Test void retirementActivitiesHaveDifferentConsequencesWithoutChangingPension() throws Exception {
        var late=world(804,70);RetirementPolicy.retire(late);long pension=late.career.monthlyPension();
        var system=new LaterLifeSystem();var p=new StoryProgress(story("retired","later-life","CHOICE",804,804,Map.of()));
        system.act("volunteer",late,p);assertThat(late.state.stats().aura()).isEqualTo(4);assertThat(late.state.stats().health()).isEqualTo(69);
        system.act("later_life_review",late,p);assertThat(late.state.stats().aura()).isEqualTo(5);
        assertThat(late.career.monthlyPay()).isZero();assertThat(late.career.monthlyPension()).isEqualTo(pension);
    }
    @Test void partTimeIsARealLowerPaidJobWithItsOwnEmploymentRecord() throws Exception {
        var full=world(360,80);var part=world(360,80);CareerSystem.leave(full,"test");CareerSystem.leave(part,"test");
        var career=new CareerSystem();
        for(int i=0;i<30&&!full.employed();i++) {
            career.act("apply_job",full,new StoryProgress(story("full","employment","CHOICE",360,360,Map.of())));
            career.act("apply_part_time",part,new StoryProgress(story("part","employment","CHOICE",360,360,Map.of())));
        }
        assertThat(full.employed()).isTrue();assertThat(part.employed()).isTrue();
        assertThat(part.career.monthlyPay()).isEqualTo(full.career.monthlyPay()*60/100);
        assertThat(part.career.position()).isEqualTo("Неполный день");assertThat(part.career.history().getLast().beganMonth()).isEqualTo(360);
        assertThat(part.career.risk()).isLessThan(full.career.risk());
    }
}
