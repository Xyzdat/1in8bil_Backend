package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import java.math.BigDecimal;
import java.util.*;
import static com.life.life_backend.economy.LocalBudget.money;

/** Public projection: no random seed, hidden outcomes, future queue or internal cents. */
public record AdultView(String modelVersion,String ageStage,String currency,AdultState.Traits traits,
        AdultState.Education education,Career career,AdultState.Partnership partnership,Map<String,Npc> npcs,
        List<AdultState.Disease> healthConditions,List<Debt> debts,List<Asset> assets,List<Story> stories,
        List<AdultState.Memory> memories,String housing,List<String> assumptions) {
    public static AdultView from(AdultState a,int month) {
        if(a==null)return null;var c=a.career();var people=new TreeMap<String,Npc>();
        a.npcs().forEach((id,n)->people.put(id,new Npc(id,n.traits(),n.education(),n.occupation(),money(n.monthlyIncome()),n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds())));
        return new AdultView(a.modelVersion(),month<360?"EARLY_ADULT":month<600?"MIDDLE_ADULT":month<780?"LATE_CAREER":"LATER_LIFE","RUB",a.traits(),a.education(),
                new Career(c.status(),c.occupation(),c.industry(),c.employer(),c.position(),money(c.monthlyPay()),c.beganMonth(),c.experienceMonths(),c.qualification(),c.satisfaction(),c.stability(),c.risk(),money(c.monthlyPension()),c.history()),
                a.partnership(),people,a.diseases(),a.debts().stream().map(d->new Debt(d.id(),d.kind(),money(d.principal()),d.annualBasisPoints(),money(d.monthlyPayment()),d.missedPayments())).toList(),
                a.assets().stream().map(x->new Asset(x.id(),x.kind(),x.title(),money(x.value()),x.ownershipPercent(),x.purchaseId())).toList(),
                a.stories().stream().map(s->new Story(s.id(),s.template(),s.stage(),s.status(),s.beganMonth(),s.endedMonth(),s.participants())).toList(),a.memories(),a.housing(),
                List.of("Wages, costs, hiring, family formation, education, diagnoses, inheritance shares and pension rules are gameplay assumptions, not national statistics or law.",
                        "WDI GDP only scales a bounded gameplay index; RUB amounts are not a conversion of USD or PPP.",
                        "UN WPP all-cause mortality remains authoritative. Disease affects the existing health modifier, not a second death draw.",
                        "NPC lives are simplified. Genealogy is limited to 120 tracked people; no runtime language model is used."));
    }
    public static FinanceView.Summary finances(AdultState a,LocalBudget budget) {
        var base=budget.summary();if(a==null)return base;
        long loans=a.debts().stream().mapToLong(AdultState.Debt::principal).sum();
        long assets=a.assets().stream().mapToLong(x->x.value()*x.ownershipPercent()/100).sum();
        // A normal shop purchase can precede the next simulation step.
        assets+=budget.purchases().stream().filter(p->p.categoryId().equals("property")&&a.assets().stream().noneMatch(x->p.id().toString().equals(x.purchaseId()))).mapToLong(LocalBudget.Purchase::price).sum();
        String occupation=a.career().occupation()==null?(a.career().monthlyPension()>0?"Пенсионер":"Поиск работы"):a.career().occupation();
        return new FinanceView.Summary(base.income(),base.expenses(),base.periodMonths(),base.savings(),money(loans+Math.max(0,-budget.balance())),base.availableBalance(),
                money(budget.balance()+assets-loans),occupation,base.depositRateBasisPoints(),base.asOfAge());
    }
    public record Career(String status,String occupation,String industry,String employer,String position,BigDecimal monthlyPay,int beganMonth,
            int experienceMonths,int qualification,int satisfaction,int stability,int risk,BigDecimal monthlyPension,List<AdultState.Employment> history){}
    public record Npc(String id,AdultState.Traits traits,String education,String occupation,BigDecimal monthlyIncome,int health,String maritalStatus,
            String partnerId,String countryIso2,List<String> parentIds){}
    public record Debt(String id,String kind,BigDecimal principal,int annualBasisPoints,BigDecimal monthlyPayment,int missedPayments){}
    public record Asset(String id,String kind,String title,BigDecimal value,int ownershipPercent,String purchaseId){}
    public record Story(String id,String template,String stage,String status,int beganMonth,Integer endedMonth,List<String> participants){}
}
