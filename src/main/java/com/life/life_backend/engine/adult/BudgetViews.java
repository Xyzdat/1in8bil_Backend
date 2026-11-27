package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import java.math.*;
import java.util.*;
import static com.life.life_backend.economy.LocalBudget.money;

public final class BudgetViews {
    private BudgetViews() { }
    public record Page(long version,String currency,Integer periodMonths,boolean available,String reason,Object data) { }
    public record Line(String id,String title,BigDecimal amount) { }
    public record Employment(String id,String title,String status,BigDecimal basePay,Integer periodMonths,Integer hoursPerWeek,String schedule,int currentTenureMonths,int totalExperienceMonths,
                             int workCapacityPercent,BigDecimal expectedPay,BigDecimal actualIncome,int actualPeriodMonths) { }
    public record Income(BigDecimal total,BigDecimal monthlyAverage,List<Line> sources,Employment primary,Employment secondary,BusinessResult business,Storage storage) { }
    public record BusinessResult(String id,String title,BigDecimal revenue,BigDecimal costs,BigDecimal net,boolean grossBreakdownKnown) { }
    public record Storage(String selectedId,BigDecimal amount,BigDecimal actualReturn,String basis) { }
    public record Expenses(BigDecimal total,BigDecimal monthlyAverage,List<Line> categories,List<Obligation> obligations,Forecast forecast) { }
    public record Obligation(String id,String title,String type,BigDecimal principal,int annualBasisPoints,BigDecimal nextMonthlyInterest,BigDecimal plannedPrincipalPayment,Integer scheduledMonthsRemaining,String assetId) { }
    public record Forecast(int months,BigDecimal balance,BigDecimal available,BigDecimal expectedIncome,BigDecimal requiredPayments,BigDecimal projectedBalance,BigDecimal deficit,String assumption) { }
    public record JobOffer(String id,String title,BigDecimal basePay,int periodMonths,int hoursPerWeek,String schedule,String education,int minQualification,int minExperienceMonths,boolean noExperience,boolean experienceMatch,boolean available,String reason) { }
    public record RaiseOffer(String id,BigDecimal currentPay,BigDecimal proposedPay,int extraHours,boolean guaranteed,boolean available,String reason) { }
    public record Jobs(List<JobOffer> offers,List<RaiseOffer> raiseGrounds,Employment current,boolean canQuit,String quitReason) { }
    public record BusinessOffer(String id,String title,BigDecimal investment,BigDecimal currentNetTarget,int periodMonths,int risk,String expectation,boolean available,String reason) { }
    public record Businesses(List<BusinessOffer> offers,BusinessResult current,String compatibility) { }
    public record StrategyOffer(String id,String title,String description,String risk,boolean available,String reason,Integer annualBasisPoints) { }
    public record Strategies(String selectedId,List<StrategyOffer> options,Storage current) { }
    public record Asset(String id,String title,String kind,int ownershipPercent,BigDecimal salePrice,List<String> debtIds,boolean available,String reason) { }
    public record SaleRecord(String assetId,String title,int month,BigDecimal proceeds,BigDecimal debtRepaid,BigDecimal net) { }
    public record Assets(List<Asset> assets,List<SaleRecord> sales) { }
    public record SalePreview(String assetId,String title,BigDecimal salePrice,BigDecimal debtPayoff,BigDecimal net,BigDecimal balanceAfterSale,
                              List<String> obligationIds,BigDecimal yearlyUpkeepStopped,BigDecimal rentalIncomeStopped,boolean rentalModelled,Forecast forecast,boolean available,String reason,String priceRule) { }

    static Page view(AdultWorld w,long version,String section,SpendingService spending,String assetId){
        String blocked=BudgetCommands.blocked(w);Object data;
        switch(section){
            case "income" -> {var summary=AdultView.finances(w.snapshot(),w.budget);data=new Income(summary.income(),average(summary.income()),incomeLines(w),employment(w,false),employment(w,true),business(w),storage(w));}
            case "expenses" -> {var summary=AdultView.finances(w.snapshot(),w.budget);data=new Expenses(summary.expenses(),average(summary.expenses()),expenseLines(w),obligations(w),forecast(w,spending));}
            case "jobs", "side-jobs" -> {
                boolean secondary=section.equals("side-jobs");
                var offers=BudgetCatalog.JOBS.stream().filter(j->j.secondary()==secondary).map(j->{String reason=BudgetCommands.jobReason(w,j);
                    return new JobOffer(j.id(),j.title(),money(w.salary(w.cost(j.payUnits()),j.title())),1,j.hours(),j.hours()==40?"Полный день":"Неполная занятость",j.education(),j.qualification(),j.experience(),j.experience()==0,w.career.experienceMonths()>=j.experience(),reason==null,reason);}).toList();
                var raises=secondary?List.<RaiseOffer>of():List.of("results","tenure","responsibility").stream().map(id->{String reason=BudgetCommands.raiseReason(w,id);return new RaiseOffer(id,money(w.career.monthlyPay()),money(w.career.monthlyPay()*(100+BudgetCommands.raisePercent(id))/100),id.equals("responsibility")?4:0,false,reason==null,reason);}).toList();
                var current=employment(w,secondary);String quit=blocked!=null?blocked:current==null?"Работа отсутствует":current.status().equals("BUSINESS")?"Закрытие бизнеса через карьерную кнопку не поддерживается":null;
                data=new Jobs(offers,raises,current,quit==null,quit);
            }
            case "businesses" -> data=new Businesses(BudgetCatalog.BUSINESSES.stream().map(b->{String reason=BudgetCommands.businessReason(w,b);return new BusinessOffer(b.id(),b.title(),money(w.cost(b.investmentUnits())),money(w.cost(b.netUnits())),1,b.risk(),"Игровая стартовая цель; будущий результат меняется сюжетами и работоспособностью, прибыль не гарантирована",reason==null,reason);}).toList(),business(w),"Бизнес заменяет основную работу; подработка с бизнесом несовместима. Используется общая career и предпринимательские сюжеты.");
            case "strategies" -> data=new Strategies(strategy(w),BudgetCatalog.STRATEGIES.stream().map(s->{String reason=blocked!=null?blocked:s.implemented()?null:s.description();return new StrategyOffer(s.id(),s.title(),s.description(),s.risk(),reason==null,reason,s.implemented()?s.annualBasisPoints():null);}).toList(),storage(w));
            case "assets" -> data=new Assets(w.assets.stream().map(a->{String reason=AssetSales.reason(w,a);return new Asset(a.id(),a.title(),a.kind(),a.ownershipPercent(),money(AssetSales.price(w,a)),w.debts.stream().filter(d->d.principal()>0&&AssetSales.linked(w,d,a)).map(AdultState.Debt::id).toList(),reason==null,reason);}).toList(),w.management.sales.stream().map(s->new SaleRecord(s.assetId(),s.title(),s.month(),money(s.proceeds()),money(s.debtRepaid()),money(s.net()))).toList());
            case "sale-preview" -> data=salePreview(w,assetId,spending);
            default -> throw BudgetCommands.invalid("Unknown budget section");
        }
        return new Page(version,"RUB",12,true,null,data);
    }
    private static BigDecimal average(BigDecimal amount){return amount.divide(BigDecimal.valueOf(12),2,RoundingMode.HALF_UP);}
    static Employment employment(AdultWorld w,boolean secondary){
        int capacity=HealthSystem.capacity(w);
        if(secondary){var s=w.management.secondary;return s==null?null:new Employment(s.id(),s.title(),"EMPLOYED",money(s.basePay()),1,s.hours(),"Подработка",Math.max(0,w.month()-s.beganMonth()),s.experienceMonths(),capacity,money(s.basePay()*capacity/100),actualPay(w,s.id(),"SECONDARY_SALARY"),12);}
        var c=w.career;if(!w.employed())return null;
        return new Employment(CareerSystem.currentJob(c),c.occupation(),c.status(),money(c.monthlyPay()),1,w.management.mainHours,w.management.mainHours==null?null:w.management.mainHours==40?"Полный день":"Индивидуальная нагрузка",Math.max(0,w.month()-c.beganMonth()),c.experienceMonths(),capacity,
                c.status().equals("BUSINESS")?null:money(c.monthlyPay()*capacity/100),c.status().equals("BUSINESS")?null:actualPay(w,CareerSystem.currentJob(c),"SALARY"),12);
    }
    private static BigDecimal actualPay(AdultWorld w,String id,String source){
        if(source.equals("SALARY")&&recent(w).stream().anyMatch(e->CashTags.resolve(e,w.budget.purchases()).sourceId().equals("LEGACY_EARNINGS")))return null;
        return money(recent(w).stream().filter(e->{var tags=CashTags.resolve(e,w.budget.purchases());return source.equals(tags.sourceId())&&id.equals(tags.relatedId());}).mapToLong(LocalBudget.Entry::income).sum());
    }
    private static List<LocalBudget.Entry> recent(AdultWorld w){return w.budget.entries().stream().filter(e->e.month()>w.month()-12&&e.month()<=w.month()&&!"FINANCING".equals(e.flow())).toList();}
    private static List<Line> incomeLines(AdultWorld w){var totals=new TreeMap<String,Long>();for(var e:recent(w))if(e.income()>0)totals.merge(CashTags.resolve(e,w.budget.purchases()).sourceId(),e.income(),Long::sum);return totals.entrySet().stream().map(e->new Line(e.getKey(),incomeTitle(e.getKey()),money(e.getValue()))).toList();}
    private static String incomeTitle(String key){return switch(key){case "SALARY"->"Основная работа";case "SECONDARY_SALARY"->"Подработка";case "PARTNER_CONTRIBUTION"->"Вклад партнёра";case "SAVINGS_RETURN"->"Доход накоплений";case "BUSINESS_REVENUE"->"Выручка бизнеса";case "PENSION"->"Пенсия";case "INVESTMENT_PROFIT"->"Реализованная прибыль";case "ALLOWANCE"->"Карманные деньги и подработка прежней модели";default->"Прочие поступления";};}
    private static final Map<String,String> CATEGORIES=Map.of("HOUSING","Жильё и коммунальные услуги","FOOD_SHOPPING","Питание и покупки","PROPERTY","Имущество и содержание","CHILDREN_EDUCATION","Дети и образование","PETS","Питомцы","HEALTH","Здоровье","LEISURE_GIFTS","Досуг и подарки","OTHER","Прочее");
    private static List<Line> expenseLines(AdultWorld w){var totals=new TreeMap<String,Long>();CATEGORIES.keySet().forEach(key->totals.put(key,0L));for(var e:recent(w))totals.merge(CashTags.resolve(e,w.budget.purchases()).categoryId(),e.expense(),Long::sum);return totals.entrySet().stream().map(e->new Line(e.getKey(),CATEGORIES.getOrDefault(e.getKey(),e.getKey()),money(e.getValue()))).toList();}
    static String strategy(AdultWorld w){return w.management.strategy==null?"deposit":w.management.strategy;}
    private static Storage storage(AdultWorld w){long actual=recent(w).stream().filter(e->CashTags.resolve(e,w.budget.purchases()).sourceId().equals("SAVINGS_RETURN")).mapToLong(e->e.income()-e.expense()).sum();return new Storage(strategy(w),money(w.budget.available()),money(actual),"Игровая номинальная ставка на положительный денежный остаток; отдельного инвестиционного кошелька нет");}
    private static BusinessResult business(AdultWorld w){
        if(!w.career.status().equals("BUSINESS"))return null;
        long revenue=0,cost=0,legacyNet=0;
        boolean unknownLegacy=false;
        for(var entry:recent(w)){
            var tag=CashTags.resolve(entry,w.budget.purchases());
            if(tag.sourceId().equals("LEGACY_EARNINGS"))unknownLegacy=true;
            if(!CareerSystem.currentJob(w.career).equals(tag.relatedId()))continue;
            switch(tag.sourceId()){
                case "BUSINESS_REVENUE" -> revenue+=entry.income();
                case "BUSINESS_COST" -> cost+=entry.expense();
                case "BUSINESS_NET" -> legacyNet+=entry.income();
                default -> { }
            }
        }
        boolean known=w.management.business!=null;
        return new BusinessResult(CareerSystem.currentJob(w.career),known?w.management.business.title():w.career.employer(),
                known?money(revenue):null,known?money(cost):null,!known&&unknownLegacy?null:money(revenue-cost+legacyNet),known);
    }
    private static List<Obligation> obligations(AdultWorld w){return w.debts.stream().filter(d->d.principal()>0).map(d->new Obligation(d.id(),d.kind().equals("MORTGAGE")?"Ипотека":"Заём",d.kind(),money(d.principal()),d.annualBasisPoints(),money(d.principal()*d.annualBasisPoints()/120000),money(Math.min(d.principal(),d.monthlyPayment())),null,w.assets.stream().filter(a->AssetSales.linked(w,d,a)).map(AdultState.Asset::id).findFirst().orElse(null))).toList();}
    static AdultWorld copy(AdultWorld w){var copy=new AdultWorld(w.state,w.sim,w.budget,w.snapshot(),w.location,w.countries,w.priceIndex);copy.propertyPrices=w.propertyPrices;return copy;}
    static Forecast forecast(AdultWorld w,SpendingService spending){
        var copy=copy(w);int from=copy.budget.entries().size();long unfundedPrincipal=0;
        if(w.state.status()!=com.life.life_backend.engine.LifeState.Status.COMPLETE)for(int m=w.month()+1;m<=w.month()+2;m++){
            long planned=copy.debts.stream().mapToLong(d->Math.min(d.principal(),d.monthlyPayment())).sum();
            long beforePrincipal=copy.debts.stream().mapToLong(AdultState.Debt::principal).sum();
            if((m-1)/12<18)copy.budget=LocalBudget.atMonth(copy.budget,m);else new AdultEconomy(spending).settle(copy,m);
            long paid=beforePrincipal-copy.debts.stream().mapToLong(AdultState.Debt::principal).sum();
            unfundedPrincipal+=Math.max(0,planned-paid);
            copy.time(m);if(m>=780&&!copy.career.status().equals("RETIRED"))RetirementPolicy.retire(copy);
        }
        var entries=copy.budget.entries().subList(from,copy.budget.entries().size());long income=entries.stream().mapToLong(LocalBudget.Entry::income).sum(),expense=entries.stream().mapToLong(LocalBudget.Entry::expense).sum();
        long projected=copy.budget.balance()-unfundedPrincipal;
        return new Forecast(2,money(w.budget.balance()),money(w.budget.available()),money(income),money(expense+unfundedPrincipal),money(projected),money(Math.max(0,-projected)),"При сохранении текущих условий; без будущих решений, смерти и неожиданных событий. Включает проценты и весь плановый основной платёж, в том числе непокрытую деньгами часть; срок погашения не гарантирован.");
    }
    private static SalePreview salePreview(AdultWorld w,String id,SpendingService spending){
        var asset=AssetSales.asset(w,id);String reason=AssetSales.reason(w,asset);long gross=AssetSales.price(w,asset),debt=AssetSales.payoff(w,asset);
        var copy=copy(w);Forecast forecast=null;if(reason==null){AssetSales.sell(copy,id);forecast=forecast(copy,spending);}
        long upkeep=w.budget.purchases().stream().filter(p->p.id().toString().equals(asset.purchaseId())).mapToLong(LocalBudget.Purchase::yearlyCost).sum()*100;
        return new SalePreview(id,asset.title(),money(gross),money(debt),money(gross-debt),money(w.budget.balance()+gross-debt),w.debts.stream().filter(d->d.principal()>0&&AssetSales.linked(w,d,asset)).map(AdultState.Debt::id).toList(),money(upkeep),null,false,forecast,reason==null,reason,
                asset.kind().equals("INVESTMENT")?"Текущая оценка × доля собственности":"Игровая цена: 75% учётной оценки × max(20%, 100% − 5% за полный год владения) × доля собственности. Арендный доход пока не моделируется.");
    }
}
