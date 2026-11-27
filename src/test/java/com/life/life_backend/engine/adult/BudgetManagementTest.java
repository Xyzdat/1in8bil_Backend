package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.CountryCatalog;
import com.life.life_backend.web.ApiFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetManagementTest {
    private final SpendingService spending=mock(SpendingService.class);
    private AdultWorld world(long seed) throws Exception {
        var sim=new LocalSimulation(seed,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),360,
                List.of(),Map.of(),Set.of(),Map.of(),List.of(),null);
        var state=new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(80,70,60,65,50,0),null,List.of());
        return new AdultWorld(state,sim,new LocalBudget(360,20_000_000,List.of(),List.of()),null,
                new FinanceView.Location("RU","Россия",null),new CountryCatalog(new ObjectMapper()),1);
    }
    private void employed(AdultWorld w,int hours){
        w.career=new Career("EMPLOYED","Мастер ремонта","Услуги","Практика","Специалист",2_000_000,300,60,2,80,70,20,0,
                List.of(new Employment("main-job","Мастер ремонта","Практика",300,null,null)));
        w.management.enabled=true;w.management.mainHours=hours;
    }
    private BudgetViews.Page page(AdultWorld w,String section){return BudgetViews.view(w,7,section,spending,null);}
    @Test void detailsReconcileAndPrincipalIsExcluded() throws Exception {
        var w=world(47);employed(w,40);
        w.budget=w.budget.post("loan-1","Текст не используется",500_000,0,true);
        w.debts.add(new Debt("loan-1","PERSONAL",500_000,1200,100_000,360,0,null));
        new AdultEconomy(spending).settle(w,362);w.time(362);
        var summary=AdultView.finances(w.snapshot(),w.budget);
        var income=(BudgetViews.Income)page(w,"income").data();
        var expenses=(BudgetViews.Expenses)page(w,"expenses").data();
        assertThat(income.total()).isEqualTo(summary.income());
        assertThat(expenses.total()).isEqualTo(summary.expenses());
        assertThat(income.sources().stream().map(BudgetViews.Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(income.total());
        assertThat(expenses.categories().stream().map(BudgetViews.Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(expenses.total());
        assertThat(income.primary().currentTenureMonths()).isEqualTo(62);
        assertThat(income.primary().totalExperienceMonths()).isEqualTo(62);
        assertThat(expenses.obligations().getFirst().scheduledMonthsRemaining()).isNull();
        assertThat(expenses.forecast().requiredPayments()).isPositive();
        assertThat(w.budget.entries()).anyMatch(e->"FINANCING".equals(e.flow())&&e.expense()>0);
    }
    @Test void secondaryPaysOnlyElapsedMonthsAndPrimaryQuitPreservesIt() throws Exception {
        var w=world(47);employed(w,40);long balance=w.budget.balance();
        BudgetCommands.apply(w,"TAKE_SIDE_JOB","weekend-help");
        assertThat(w.budget.balance()).isEqualTo(balance);
        var e=new AdultEconomy(spending);e.settle(w,362);w.time(362);
        assertThat(w.budget.entries()).filteredOn(x->"SECONDARY_SALARY".equals(x.sourceId())).hasSize(2);
        var settled=w.budget;e.settle(w,362);assertThat(w.budget).isEqualTo(settled);
        BudgetCommands.apply(w,"QUIT_JOB","main-job");
        assertThat(w.management.secondary).isNotNull();e.settle(w,363);w.time(363);
        assertThat(w.budget.entries()).noneMatch(x->x.id().equals("salary-363"));
        assertThat(w.budget.entries()).anyMatch(x->x.month()==363&&"SECONDARY_SALARY".equals(x.sourceId()));
        BudgetCommands.apply(w,"QUIT_SIDE_JOB",w.management.secondary.id());e.settle(w,364);
        assertThat(w.budget.entries()).noneMatch(x->x.month()==364&&"SECONDARY_SALARY".equals(x.sourceId()));
        assertThat(w.management.history).hasSize(1);
    }
    @Test void compatibilityWorkloadAndRetirementAreEnforced() throws Exception {
        var w=world(47);employed(w,60);
        assertThatThrownBy(()->BudgetCommands.apply(w,"TAKE_SIDE_JOB","weekend-help")).isInstanceOf(ApiFailure.class);
        w.management.mainHours=null;assertThat(BudgetCommands.jobReason(w,BudgetCatalog.job("weekend-help"))).contains("неизвестна");
        w.management.mainHours=40;BudgetCommands.apply(w,"TAKE_SIDE_JOB","evening-support");
        int health=w.state.stats().health(),happy=w.state.stats().happiness();new AdultEconomy(spending).settle(w,372);w.time(372);
        assertThat(w.state.stats().health()).isEqualTo(health-2);assertThat(w.state.stats().happiness()).isEqualTo(happy-2);
        RetirementPolicy.retire(w);assertThat(w.management.secondary).isNull();assertThat(w.management.history).hasSize(1);
    }
    @Test void hiringAndRaiseHaveBothPureDeclinesAndAcceptedResults() throws Exception {
        boolean accepted=false,declined=false,raiseAccepted=false,raiseDeclined=false;
        for(long seed=1;seed<=40;seed++){
            var w=world(seed);var before=w.snapshot();var budget=w.budget;
            var result=BudgetCommands.apply(w,"APPLY_JOB","warehouse");
            if(result.status().equals("DECLINED")){
                declined=true;assertThat(w.snapshot()).isEqualTo(before);assertThat(w.budget).isEqualTo(budget);
                assertThat(BudgetCommands.apply(w,"APPLY_JOB","warehouse")).isEqualTo(result);
            }else{accepted=true;assertThat(w.career.status()).isEqualTo("EMPLOYED");assertThat(w.budget).isEqualTo(budget);}
            employed(w,40);before=w.snapshot();
            var raised=BudgetCommands.apply(w,"REQUEST_RAISE","results");
            if(raised.status().equals("DECLINED")){raiseDeclined=true;assertThat(w.snapshot()).isEqualTo(before);}
            else{raiseAccepted=true;assertThat(w.career.monthlyPay()).isEqualTo(2_300_000);assertThat(BudgetCommands.raiseReason(w,"results")).isNotNull();}
        }
        assertThat(accepted&&declined&&raiseAccepted&&raiseDeclined).isTrue();
    }
    @Test void jobFiltersAndReplacementCloseOnlyOldPrimary() throws Exception {
        var w=world(47);employed(w,40);
        assertThat(BudgetCommands.jobReason(w,BudgetCatalog.job("analyst"))).contains("образование");
        BudgetCommands.apply(w,"TAKE_SIDE_JOB","weekend-help");
        // Search deterministic seeds until an accepted application, preserving the same initial career fixture.
        boolean done=false;
        for(int seed=1;seed<40&&!done;seed++){
            var candidate=world(seed);employed(candidate,40);
            if(BudgetCommands.apply(candidate,"APPLY_JOB","warehouse").status().equals("APPLIED")){
                done=true;assertThat(candidate.career.history().getFirst().endedMonth()).isEqualTo(360);
                assertThat(candidate.career.history().getLast().endedMonth()).isNull();
            }
        }
        assertThat(done).isTrue();
    }
    @Test void businessSharesCareerAndSeparatesGrossCostAndNet() throws Exception {
        var w=world(47);employed(w,40);long balance=w.budget.balance();
        BudgetCommands.apply(w,"OPEN_BUSINESS","workshop");
        assertThat(w.budget.balance()).isEqualTo(balance-w.cost(20));assertThat(w.career.status()).isEqualTo("BUSINESS");
        assertThat(w.career.history().getFirst().endedMonth()).isEqualTo(360);
        assertThat(w.stories).anyMatch(s->s.template().equals("business-start")&&s.stage().equals("REVIEW"));
        assertThatThrownBy(()->BudgetCommands.apply(w,"OPEN_BUSINESS","workshop")).isInstanceOf(ApiFailure.class);
        new AdultEconomy(spending).settle(w,362);w.time(362);
        var income=(BudgetViews.Income)page(w,"income").data();
        assertThat(income.business().grossBreakdownKnown()).isTrue();
        assertThat(income.business().net()).isEqualByComparingTo(income.business().revenue().subtract(income.business().costs()));
        assertThat(w.budget.entries()).filteredOn(e->"BUSINESS_START".equals(e.sourceId())).hasSize(1);
        assertThat(w.budget.entries()).noneMatch(e->e.id().startsWith("salary-"));
    }
    @Test void strategyIsPersistedAndSelectionDoesNotMoveCash() throws Exception {
        var w=world(47);long balance=w.budget.balance();BudgetCommands.apply(w,"SET_STRATEGY","cash");
        assertThat(w.budget.balance()).isEqualTo(balance);new AdultEconomy(spending).settle(w,361);w.time(361);
        assertThat(w.budget.entries()).noneMatch(e->e.id().startsWith("interest-"));
        assertThat(AdultView.finances(w.snapshot(),w.budget).depositRateBasisPoints()).isZero();
        BudgetCommands.apply(w,"SET_STRATEGY","deposit");new AdultEconomy(spending).settle(w,362);w.time(362);
        assertThat(w.budget.entries()).anyMatch(e->e.id().equals("interest-362")&&e.income()>0);
        assertThatThrownBy(()->BudgetCommands.apply(w,"SET_STRATEGY","trading")).isInstanceOf(ApiFailure.class);
        var saved=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(w.snapshot()),AdultState.class);
        assertThat(saved).isEqualTo(w.snapshot());
    }
    private String property(AdultWorld w,long debt){
        var p=new LocalBudget.Purchase(UUID.randomUUID(),360,"property","motorcycle","Транспорт",null,2_000_000,12000,null,null,null);
        w.budget=w.budget.book(p);w.synchronizeAssets();String id=w.assets.getLast().id();
        if(debt>0)w.debts.add(new Debt("secured","PERSONAL",debt,1200,10000,360,0,id));return id;
    }
    @Test void salePreviewIsPureAndSalePaysOnlyLinkedDebtAndStopsUpkeep() throws Exception {
        for(long principal:new long[]{0,500_000}){
            var w=world(47);String id=property(w,principal);w.debts.add(new Debt("unrelated","PERSONAL",10000,0,1000,360,0,null));
            var before=w.snapshot();var budget=w.budget;
            var preview=(BudgetViews.SalePreview)BudgetViews.view(w,7,"sale-preview",spending,id).data();
            assertThat(w.snapshot()).isEqualTo(before);assertThat(w.budget).isEqualTo(budget);
            assertThat(preview.salePrice()).isEqualByComparingTo("15000.00");
            assertThat(preview.debtPayoff()).isEqualByComparingTo(LocalBudget.money(principal));
            BudgetCommands.apply(w,"SELL_ASSET",id);
            assertThat(w.budget.balance()).isEqualTo(budget.balance()+1_500_000-principal);
            assertThat(w.assets).isEmpty();assertThat(w.budget.purchases().getFirst().soldMonth()).isEqualTo(360);
            assertThat(w.debts.stream().filter(d->d.id().equals("unrelated")).findFirst().orElseThrow().principal()).isEqualTo(10000);
            assertThatThrownBy(()->BudgetCommands.apply(w,"SELL_ASSET",id)).isInstanceOf(ApiFailure.class);
            new AdultEconomy(spending).settle(w,362);
            assertThat(w.budget.entries()).noneMatch(e->e.id().startsWith("upkeep-"));
            assertThat(w.management.sales).hasSize(1);
        }
    }
    @Test void insufficientCollateralSaleCannotMutateAndLegacyLinkDoesNotAttachToRebuy() throws Exception {
        var w=world(47);String id=property(w,50_000_000);var before=w.snapshot();var budget=w.budget;
        assertThatThrownBy(()->BudgetCommands.apply(w,"SELL_ASSET",id)).isInstanceOf(ApiFailure.class);
        assertThat(w.snapshot()).isEqualTo(before);assertThat(w.budget).isEqualTo(budget);
        var d=new Debt("legacy","PERSONAL",10000,0,100,360,0,"motorcycle");w.debts.clear();w.debts.add(d);
        assertThat(AssetSales.linked(w,d,w.assets.getFirst())).isTrue();BudgetCommands.apply(w,"SELL_ASSET",id);w.time(362);
        String later=property(w,0);assertThat(later).isNotEqualTo(id);assertThat(AssetSales.linked(w,d,w.assets.getLast())).isFalse();
    }
    @Test void pendingAndCompleteBlockAllMutationsAndOldJsonStillLoads() throws Exception {
        var w=world(47);var state=w.state;
        w.state=new LifeState(30,LifeState.Status.ACTIVE,state.stats(),new LifeState.Decision("d","Выбор",List.of()),List.of());
        assertThatThrownBy(()->BudgetCommands.apply(w,"SET_STRATEGY","cash")).isInstanceOf(ApiFailure.class);
        w.state=new LifeState(30,LifeState.Status.COMPLETE,state.stats(),null,List.of());
        assertThatThrownBy(()->BudgetCommands.apply(w,"SET_STRATEGY","cash")).isInstanceOf(ApiFailure.class);
        var json=new ObjectMapper();var node=json.valueToTree(w.snapshot()).deepCopy();((tools.jackson.databind.node.ObjectNode)node).remove("budgetManagement");
        assertThat(json.treeToValue(node,AdultState.class).budgetManagement()).isNull();
        var old=json.readValue("{\"month\":360,\"balance\":0,\"entries\":[{\"id\":\"salary-360\",\"month\":360,\"kind\":\"BUDGET\",\"title\":\"Не категория\",\"income\":100,\"expense\":0}],\"purchases\":[]}",LocalBudget.class);
        assertThat(CashTags.resolve(old.entries().getFirst(),List.of()).sourceId()).isEqualTo("LEGACY_EARNINGS");
    }
    @Test void forecastIncludesUnfundedPrincipalAndReconciles() throws Exception {
        var w=world(47);w.budget=new LocalBudget(360,0,List.of(),List.of());
        w.debts.add(new Debt("debt","PERSONAL",1_000_000,1200,100_000,360,0,null));
        var forecast=BudgetViews.forecast(w,spending);
        assertThat(forecast.requiredPayments()).isGreaterThanOrEqualTo(new BigDecimal("2000.00"));
        assertThat(forecast.projectedBalance()).isEqualByComparingTo(forecast.balance().add(forecast.expectedIncome()).subtract(forecast.requiredPayments()));
        assertThat(forecast.deficit()).isPositive();assertThat(w.budget.balance()).isZero();
        assertThat(w.debts.getFirst().principal()).isEqualTo(1_000_000);
    }
    @Test void partnerContributionAndBaseVersusActualPayAreNotConfused() throws Exception {
        var w=world(47);employed(w,40);String id=w.newPerson("partner","FEMALE",0,List.of());
        var npc=w.npcs.get(id);w.npcs.put(id,new Npc(id,npc.traits(),npc.education(),npc.occupation(),1_000_000,80,"MARRIED",null,"RU",List.of(),0,Set.of()));
        w.partnership=new Partnership(id,"MARRIED",300,true,80,0,List.of());
        w.diseases.add(new Disease("injury","INJURY",360,40,"ACTIVE","SUPERVISED",366,null,false,50));
        new AdultEconomy(spending).settle(w,361);w.time(361);
        var income=(BudgetViews.Income)page(w,"income").data();
        assertThat(income.primary().basePay()).isEqualByComparingTo("20000.00");
        assertThat(income.primary().actualIncome()).isEqualByComparingTo("10000.00");
        assertThat(income.primary().expectedPay()).isEqualByComparingTo("10000.00");
        assertThat(income.sources().stream().filter(l->l.id().equals("PARTNER_CONTRIBUTION")).findFirst().orElseThrow().amount()).isEqualByComparingTo("5000.00");
    }
    @Test void terminalWorkloadCannotBeHealedByYearFocus() throws Exception {
        var w=world(47);employed(w,40);BudgetCommands.apply(w,"TAKE_SIDE_JOB","evening-support");
        w.time(370);w.budget=new LocalBudget(370,w.budget.balance(),List.of(),List.of());
        w.state=new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(1,70,60,65,50,0),null,List.of());
        w.sim=w.sim.withFocus(new com.life.life_backend.engine.focus.FocusState("year-focus-1","health",370,370,Map.of("stat:health",10),List.of(),null));
        when(spending.shop(any(),eq("property"))).thenReturn(new FinanceView.Shop(1,30,FinanceView.Currency.RUB,null,"","","",BigDecimal.ZERO,null,List.of(),List.of()));
        var json=new ObjectMapper();
        var engine=new AdultLifeEngine(new AdultStoryCatalog(json),new LocalSimulationEngine(new EventCatalog(json),mock(MortalityModel.class)),
                mock(com.life.life_backend.statistics.CountryReference.class),new CountryCatalog(json),spending);
        var result=engine.step(w.state,w.sim,w.snapshot(),w.budget,w.location);
        assertThat(result.state().stats().health()).isZero();
        assertThat(result.state().status()).isEqualTo(LifeState.Status.COMPLETE);
        assertThat(result.adult().budgetManagement().secondary()).isNull();
    }
}
