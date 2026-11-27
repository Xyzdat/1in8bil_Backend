package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

final class AdultEconomy {
    static final Set<String> ACTIONS=Set.of("loan","cut_costs","debt_review","mortgage","buy_home","home_review","buy_vehicle",
            "vehicle_review","invest","investment_review","investment_sell","inherit","inherit_review");
    private final SpendingService spending;
    AdultEconomy(SpendingService spending) { this.spending=spending; }

    void settle(AdultWorld w,int target) {
        for(int m=w.budget.month()+1;m<=target;m++) {
            final int settledMonth=m;
            w.budget=w.budget.adultMonth(m,BudgetCatalog.effectiveRate(w)); var c=w.career;
            long pay=w.employed()?c.monthlyPay()*HealthSystem.capacity(w)/100:0;
            if(c.status().equals("BUSINESS")&&w.management.business!=null){
                long costs=w.management.business.monthlyCost();
                long revenue=(c.monthlyPay()+costs)*HealthSystem.capacity(w)/100;
                w.budget=w.budget.postTagged("business-revenue-"+m,"Выручка бизнеса",revenue,0,false,"BUSINESS_REVENUE","OTHER",CareerSystem.currentJob(c));
                w.budget=w.budget.postTagged("business-cost-"+m,"Расходы бизнеса",0,costs,false,"BUSINESS_COST","OTHER",CareerSystem.currentJob(c));
            }else if(pay>0)w.budget=w.budget.postTagged("salary-"+m,c.status().equals("BUSINESS")?"Доход собственного дела":"Зарплата: "+c.occupation(),pay,0,false,c.status().equals("BUSINESS")?"BUSINESS_NET":"SALARY","OTHER",CareerSystem.currentJob(c));
            var secondary=w.management.secondary;
            if(secondary!=null){
                long sidePay=secondary.basePay()*HealthSystem.capacity(w)/100;
                w.budget=w.budget.postTagged("secondary-"+secondary.id()+"-"+m,"Подработка: "+secondary.title(),sidePay,0,false,"SECONDARY_SALARY","OTHER",secondary.id());
                w.management.secondary=new BudgetState.SideJob(secondary.id(),secondary.offerId(),secondary.title(),secondary.basePay(),secondary.hours(),secondary.beganMonth(),secondary.experienceMonths()+1,null);
                if(m%12==0&&(w.employed()&&w.management.mainHours!=null?w.management.mainHours:0)+secondary.hours()>48)w.stats(-2,-2,0,0);
            }
            if(c.monthlyPension()>0)post(w,"pension-"+m,"Пенсионная выплата",c.monthlyPension(),0,false);
            long contribution=0;
            if(w.coupled()&&w.partnership.cohabiting())contribution=w.npcs.get(w.partnership.partnerId()).monthlyIncome()/2;
            if(contribution>0)post(w,"partner-"+m,"Вклад партнёра в общий бюджет",contribution,0,false);
            long living=w.cost(w.housing.equals("FAMILY_HOME")?5:8);
            if(w.coupled()&&w.partnership.cohabiting())living+=w.cost(3);
            if(w.flags.contains("frugal"))living=living*80/100;
            post(w,"living-housing-"+m,"Жильё и коммунальные услуги",0,living*40/100,false);
            post(w,"living-food-"+m,"Питание и повседневные покупки",0,living-living*40/100,false);
            long children=w.children().stream().filter(p -> settledMonth-p.birthMonth()<216).count();
            if(children>0)post(w,"children-"+m,"Содержание и поддержка детей",0,children*w.cost(2),false);
            long medical=w.diseases.stream().filter(d -> d.endedMonth()==null&&d.treatment().equals("SUPERVISED")).count()*w.cost(1);
            if(medical>0)post(w,"treatment-"+m,"Лечение и наблюдение",0,medical,false);
            settleDebts(w,m);
            if(w.employed())w.career=new Career(c.status(),c.occupation(),c.industry(),c.employer(),c.position(),c.monthlyPay(),c.beganMonth(),
                    c.experienceMonths()+1,c.qualification(),c.satisfaction(),c.stability(),c.risk(),c.monthlyPension(),c.history());
            for(var purchase:w.budget.purchases()) if(purchase.completionMonth()!=null && purchase.completionMonth()==m)CareerSystem.qualify(w,1);
        }
    }
    private void settleDebts(AdultWorld w,int month) {
        for(int i=0;i<w.debts.size();i++) {
            var d=w.debts.get(i); if(d.principal()<=0)continue;
            long interest=d.principal()*d.annualBasisPoints()/120000;
            if(interest>0)post(w,"loan-interest-"+d.id()+"-"+month,"Проценты по обязательству",0,interest,false);
            long principal=Math.min(d.principal(),Math.min(d.monthlyPayment(),w.budget.available()));
            if(principal>0)post(w,"loan-principal-"+d.id()+"-"+month,"Погашение основного долга",0,principal,true);
            w.debts.set(i,new Debt(d.id(),d.kind(),d.principal()-principal,d.annualBasisPoints(),d.monthlyPayment(),d.beganMonth(),
                    d.missedPayments()+(principal<Math.min(d.principal(),d.monthlyPayment())?1:0),d.collateralId()));
        }
    }
    StoryProgress.Result act(String action,AdultWorld w,StoryProgress p) {
        return switch(action) {
            case "loan" -> {
                if(!w.employed()||w.debts.stream().filter(d -> d.principal()>0).count()>=2)yield end("Новый заём не одобрен: текущий доход или долговая нагрузка не подходят.");
                long principal=w.cost(40); borrow(w,principal,"PERSONAL",null); yield next("Ты получаешь заём. Это деньги к возврату, а не заработок; проценты и платежи появятся в бюджете.");
            }
            case "cut_costs" -> { w.flags.add("frugal"); w.stats(0,-2,0,0); yield next("Ты сокращаешь повседневные расходы на 20%. Долги сохраняются и будут погашаться по мере появления денег."); }
            case "debt_review" -> { yield end(w.budget.balance()>=0?"Текущие поступления покрывают повседневные расходы. Остаток обязательств по-прежнему виден в бюджете.":"Расходы всё ещё превышают возможности. Долг не исчез: нужно восстановить доход или продолжить сокращение трат."); }
            case "mortgage", "buy_home" -> {
                if(w.owns("house"))yield end("Жильё уже приобретено.");
                var shop=spending.shop(new SpendingService.LocalContext(0,w.state,w.budget,w.location),"property");
                var offer=shop.offers().stream().filter(o -> o.id().equals("house")).findFirst().orElseThrow();
                long price=offer.price().movePointRight(2).longValueExact();
                if(action.equals("mortgage")) {
                    if(!w.employed()||w.budget.available()<price/5||w.debts.stream().anyMatch(d -> d.principal()>0))yield end("Ипотека не одобрена: нужен первоначальный взнос, работа и отсутствие других непогашенных займов.");
                    borrow(w,price*4/5,"MORTGAGE","house");
                }
                if(w.budget.available()<price)yield end("На выбранное жильё сейчас не хватает накоплений.");
                buy(w,p,"house"); w.housing="OWN_HOME";
                String collateral=w.assets.stream().filter(a->a.purchaseId()!=null&&w.budget.purchases().stream().anyMatch(purchase->purchase.itemId().equals("house")&&purchase.id().toString().equals(a.purchaseId())&&purchase.soldMonth()==null)).map(Asset::id).findFirst().orElseThrow();
                w.debts.replaceAll(d->"house".equals(d.collateralId())&&d.beganMonth()==w.month()?new Debt(d.id(),d.kind(),d.principal(),d.annualBasisPoints(),d.monthlyPayment(),d.beganMonth(),d.missedPayments(),collateral):d);
                yield next("Ты приобретаешь жильё. Покупка и содержание отражены в едином бюджете, а объект — в имуществе.");
            }
            case "home_review" -> { w.stats(0,4,0,0); yield end("Ты обустраиваешь дом и привыкаешь к расходам на его содержание. Право собственности остаётся частью состояния семьи."); }
            case "buy_vehicle" -> { if(w.owns("motorcycle"))yield end("Транспорт уже приобретён."); buy(w,p,"motorcycle"); yield next("Ты покупаешь мотоцикл. В бюджете учтены стоимость и дальнейшее содержание."); }
            case "vehicle_review" -> { w.stats(0,3,0,0); yield end("Ты освоился с новым транспортом и планируешь регулярные расходы на обслуживание."); }
            case "invest" -> {
                long amount=w.cost(10); if(w.budget.available()<amount)yield end("Сейчас свободных средств для вложения недостаточно.");
                String id=w.id("investment"); p.put("asset",id);
                post(w,p.story.id()+"-invest","Перевод накоплений в инвестиционный актив",0,amount,true);
                w.assets.add(new Asset(id,"INVESTMENT","Инвестиционный портфель",amount,amount,w.month(),100,null));
                yield next("Часть накоплений вложена в рискованный актив. Эти средства больше не доступны для покупок; результат может быть отрицательным.");
            }
            case "investment_review" -> {
                int i=asset(w,p.get("asset")); if(i<0)yield end("Этот актив уже закрыт."); var a=w.assets.get(i);
                int percent=w.random.nextInt(65,136); long value=a.value()*percent/100;
                w.assets.set(i,new Asset(a.id(),a.kind(),a.title(),a.purchasePrice(),value,a.acquiredMonth(),a.ownershipPercent(),a.purchaseId()));
                yield next(percent>=100?"Оценка инвестиции выросла. До продажи это ещё не доступные деньги.":"Рыночная оценка снизилась. Убыток пока выражен в стоимости актива.");
            }
            case "investment_sell" -> {
                int i=asset(w,p.get("asset")); if(i<0)yield end("Инвестиция уже закрыта.");
                AssetSales.execute(w,p.get("asset"));
                yield end("Инвестиция закрыта по текущей оценке. Полученные деньги снова доступны, прибыль или убыток учтены отдельно от возврата капитала.");
            }
            case "inherit" -> {
                var n=w.npcs.get(p.actor()); if(n==null||w.person(p.actor()).alive()||n.estate()<=0)yield end("Доступного наследства больше нет.");
                long share=n.estate()/2; post(w,p.story.id()+"-inherit","Полученное наследство",share,0,false);
                w.npcs.put(n.id(),new Npc(n.id(),n.traits(),n.education(),n.occupation(),n.monthlyIncome(),n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds(),0,n.milestones()));
                yield next("После утраты ты получаешь игровую долю наследства. Остальная часть относится к другим наследникам; повторно эти средства не начислятся.");
            }
            case "inherit_review" -> { w.stats(0,1,0,0); yield end("Финансовые дела завершены. Наследство не заменяет отношений, но помогает распорядиться оставшейся частью семейной истории."); }
            default -> throw new IllegalArgumentException(action);
        };
    }
    private void buy(AdultWorld w,StoryProgress p,String item) {
        UUID id=UUID.nameUUIDFromBytes((w.sim.seed()+":"+p.story.id()+":"+item).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var receipt=spending.purchase(new SpendingService.LocalContext(0,w.state,w.budget,w.location),id,"property",item,null);
        w.budget=receipt.budget(); w.synchronizeAssets();
        if(receipt.effects()!=null)w.state=new com.life.life_backend.engine.LifeState(w.state.age(),w.state.status(),receipt.effects().apply(w.state.stats()),w.state.pendingDecision(),w.state.history());
    }
    private static int asset(AdultWorld w,String id) { for(int i=0;i<w.assets.size();i++)if(w.assets.get(i).id().equals(id))return i;return -1; }
    private static void borrow(AdultWorld w,long amount,String kind,String collateral) {
        String id=w.id("loan"); w.debts.add(new Debt(id,kind,amount,kind.equals("MORTGAGE")?600:1200,Math.max(1,amount/(kind.equals("MORTGAGE")?120:36)),w.month(),0,collateral));
        post(w,id,"Получение займа (основной долг)",amount,0,true);
    }
    private static void post(AdultWorld w,String id,String title,long income,long expense,boolean financing) { w.budget=w.budget.post(id,title,income,expense,financing); }
}
