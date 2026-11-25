package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.CountryCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.life.life_backend.engine.adult.AdultState.*;

class AdultSystemsTest {
    private AdultWorld world() throws Exception {
        var sim=new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),360,
                List.of(new LocalSimulation.Person("mother","mother","Мама",-300,true,65,60,"FEMALE","Учитель")),Map.of(),Set.of(),Map.of(),List.of(),null);
        var state=new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(75,65,65,60,50,0),null,List.of());
        return new AdultWorld(state,sim,new LocalBudget(360,10_000_000,List.of(),List.of()),null,new FinanceView.Location("RU","Россия",null),new CountryCatalog(new ObjectMapper()),1);
    }
    private StoryProgress progress(String id,List<String> actors) {return new StoryProgress(new Story(id,"test","CHOICE","ACTIVE",360,360,null,actors,Map.of(),List.of()));}
    private void employed(AdultWorld w) {
        w.career=new Career("EMPLOYED","Мастер","Услуги","Компания","Специалист",2_000_000,300,60,2,70,70,20,0,List.of(new Employment("job-1","Мастер","Компания",300,null,null)));
    }
    @Test void salaryReplacesAllowanceAndLoanPrincipalIsNotIncome() throws Exception {
        var w=world();employed(w);var economy=new AdultEconomy(mock(SpendingService.class));
        economy.settle(w,362);var first=w.budget;
        assertThat(first.entries().stream().filter(e->e.id().startsWith("salary-")).count()).isEqualTo(2);
        assertThat(first.entries()).noneMatch(e->e.id().startsWith("budget-"));
        economy.settle(w,362);assertThat(w.budget).isEqualTo(first);
        long income=first.summary().income().movePointRight(2).longValueExact();
        economy.act("loan",w,progress("loan",List.of()));
        assertThat(w.budget.balance()).isEqualTo(first.balance()+4_000_000);
        assertThat(w.budget.summary().income().movePointRight(2).longValueExact()).isEqualTo(income);
        assertThat(w.debts.getFirst().principal()).isEqualTo(4_000_000);
        CareerSystem.leave(w,"test");economy.settle(w,363);
        assertThat(w.budget.entries()).noneMatch(e->e.id().equals("salary-363"));
        assertThat(w.career.history().getLast().endedMonth()).isNotNull();
    }
    @Test void illnessLimitsWorkWithoutSecondMortalityAndRecoveryRestoresCapacity() throws Exception {
        var w=world();employed(w);var p=progress("injury",List.of());var health=new HealthSystem();
        health.act("accident",w,p);health.act("injury_care",w,p);
        assertThat(w.diseases).hasSize(1);assertThat(HealthSystem.capacity(w)).isLessThan(100);
        var economy=new AdultEconomy(mock(SpendingService.class));economy.settle(w,361);
        assertThat(w.budget.entries().stream().filter(e->e.id().equals("salary-361")).findFirst().orElseThrow().income()).isLessThan(2_000_000);
        assertThat(w.budget.entries()).anyMatch(e->e.id().equals("treatment-361"));
        var copy=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(w.snapshot()),AdultState.class);
        assertThat(copy).isEqualTo(w.snapshot());
    }
    @Test void pregnancyKeepsOriginalParentAfterSeparationAndNoChildComesFromDeadCarrier() throws Exception {
        var w=world();String partner=w.newPerson("partner","FEMALE",w.month()-28*12,List.of());
        w.partnership=new Partnership(partner,"MARRIED",300,true,80,5,List.of());
        var p=progress("baby",List.of(partner));var family=new FamilySystem();
        for(int i=0;i<50&&!w.flags.contains("pregnancy");i++)family.act("conceive",w,p);
        assertThat(w.flags).contains("pregnancy");FamilySystem.separate(w,partner);
        String other=w.newPerson("partner","FEMALE",w.month()-25*12,List.of());
        w.partnership=new Partnership(other,"DATING",w.month(),false,70,0,List.of());w.time(370);
        var birth=family.act("birth",w,p);
        assertThat(birth.proceed()).isTrue();
        assertThat(w.npcs.get(p.get("child")).parentIds()).containsExactly("character",partner).doesNotContain(other);
        var dead=w.person(other);w.replacePerson(new LocalSimulation.Person(dead.id(),dead.role(),dead.name(),dead.birthMonth(),false,dead.closeness(),dead.trust(),dead.sex(),null,370));
        var lost=progress("lost",List.of(other));lost.put("carrier",other);lost.put("parent",other);int count=w.children().size();
        assertThat(family.act("birth",w,lost).proceed()).isFalse();assertThat(w.children()).hasSize(count);
    }
    @Test void promotionCannotApplyToAnotherJobAndLateStudyIsAllowed() throws Exception {
        var w=world();employed(w);var p=progress("promotion",List.of());var career=new CareerSystem();
        career.act("promotion",w,p);CareerSystem.leave(w,"test");
        w.career=new Career("EMPLOYED","Другая работа","Услуги","Другая компания","Новичок",1_000_000,360,60,2,60,50,20,0,List.of(new Employment("job-2","Другая работа","Другая компания",360,null,null)));
        career.act("promotion_review",w,p);assertThat(w.career.monthlyPay()).isEqualTo(1_000_000);
        w.time(70*12);assertThat(AdultConditions.test("study",w)).isTrue();
        career.act("study",w,progress("late-study",List.of()));assertThat(w.education.completesMonth()).isEqualTo(74*12);
    }
    @Test void friendHelpChangesAuraAndOnlyNamedPerson() throws Exception {
        var w=world();String friend=w.newPerson("friend","MALE",0,List.of());var before=w.state.stats();var old=w.sim;
        new FamilySystem().act("friend_talk",w,progress("friend",List.of(friend)));w.event("test","test",before,old);
        var effect=w.state.history().getLast().consequences();assertThat(effect.aura()).isEqualTo(2);assertThat(effect.closeness()).containsOnlyKeys(friend);
        assertThat(w.person("mother").closeness()).isEqualTo(65);
    }
    @Test void investmentLossIsRealizedOnceAndNeverCreatesCash() throws Exception {
        var w=world();var economy=new AdultEconomy(mock(SpendingService.class));var p=progress("investment",List.of());long before=w.budget.balance();
        economy.act("invest",w,p);var a=w.assets.getFirst();w.assets.set(0,new Asset(a.id(),a.kind(),a.title(),a.purchasePrice(),a.value()/2,a.acquiredMonth(),100,null));
        economy.act("investment_sell",w,p);assertThat(w.budget.balance()).isEqualTo(before-500_000);
        assertThat(w.budget.summary().expenses()).isEqualByComparingTo("5000.00");
        long after=w.budget.balance();economy.act("investment_sell",w,p);assertThat(w.budget.balance()).isEqualTo(after);
    }
    @Test void catalogPricesAndNormalShopPurchasesHaveOneAuthority() throws Exception {
        var w=world();w.propertyPrices=Map.of("house",100_000_000L,"motorcycle",20_000_000L);
        assertThat(AdultConditions.test("home",w)).isFalse();assertThat(AdultConditions.test("vehicle",w)).isFalse();
        w.propertyPrices=Map.of("house",30_000_000L,"motorcycle",2_000_000L);
        assertThat(AdultConditions.test("home",w)).isTrue();assertThat(AdultConditions.test("vehicle",w)).isTrue();
        var purchase=new LocalBudget.Purchase(UUID.randomUUID(),w.month(),"property","motorcycle","Транспорт",null,2_000_000,0,null,null,null);
        var updated=w.snapshot().withPurchase(purchase);
        assertThat(updated.assets()).hasSize(1);assertThat(updated.withPurchase(purchase)).isEqualTo(updated);
        var budget=w.budget.book(purchase);
        assertThat(AdultView.finances(updated,budget).netWorth()).isEqualByComparingTo(LocalBudget.money(w.budget.balance()));
    }
}
