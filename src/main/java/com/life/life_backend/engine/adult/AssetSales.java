package com.life.life_backend.engine.adult;

import java.util.*;
import static com.life.life_backend.economy.LocalBudget.money;

final class AssetSales {
    static AdultState.Asset asset(AdultWorld w,String id){return w.assets.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->BudgetCommands.conflict("ASSET_UNAVAILABLE","Актив не найден или уже продан"));}
    static long price(AdultWorld w,AdultState.Asset asset){
        long share=asset.value()*asset.ownershipPercent()/100;
        if(asset.kind().equals("INVESTMENT"))return share;
        int years=Math.max(0,w.month()-asset.acquiredMonth())/12;
        return share*75/100*Math.max(20,100-years*5)/100;
    }
    static boolean linked(AdultWorld w,AdultState.Debt debt,AdultState.Asset asset){
        if(debt.collateralId()==null)return false;
        if(debt.collateralId().equals(asset.id())||debt.collateralId().equals(asset.purchaseId()))return true;
        var purchase=w.budget.purchases().stream().filter(p->p.id().toString().equals(asset.purchaseId())).findFirst();
        if(purchase.isEmpty()||!debt.collateralId().equals(purchase.get().itemId()))return false;
        // Old saves used an item code. Resolve to the original eligible acquisition, never to an unrelated later copy.
        return w.budget.purchases().stream().filter(p->p.itemId().equals(debt.collateralId())&&p.month()<=debt.beganMonth())
                .min(Comparator.comparingInt(com.life.life_backend.economy.LocalBudget.Purchase::month)).map(p->p.id().toString().equals(asset.purchaseId())).orElse(false);
    }
    static long payoff(AdultWorld w,AdultState.Asset asset){return w.debts.stream().filter(d->linked(w,d,asset)).mapToLong(AdultState.Debt::principal).sum();}
    static String reason(AdultWorld w,AdultState.Asset asset){
        String base=BudgetCommands.blocked(w);if(base!=null)return base;
        if(price(w,asset)+w.budget.available()<payoff(w,asset))return "Цена продажи и доступные средства не покрывают обеспеченный долг";
        return null;
    }
    static BudgetOutcome sell(AdultWorld w,String id){
        BudgetCommands.require(reason(w,asset(w,id)));
        return execute(w,id);
    }
    /** Shared settlement; caller has validated either a budget command or the current story choice. */
    static BudgetOutcome execute(AdultWorld w,String id){
        var asset=asset(w,id);long gross=price(w,asset),debt=payoff(w,asset);
        String source=w.id("sale");
        if(asset.kind().equals("INVESTMENT")){
            long principal=asset.purchasePrice()*asset.ownershipPercent()/100;
            w.budget=w.budget.postTagged(source+"-capital","Возврат капитала: "+asset.title(),Math.min(principal,gross),0,true,"CAPITAL_RETURN","OTHER",id);
            if(gross>principal)w.budget=w.budget.postTagged(source+"-profit","Реализованная прибыль",gross-principal,0,false,"INVESTMENT_PROFIT","OTHER",id);
            if(gross<principal){
                w.budget=w.budget.postTagged(source+"-loss-offset","Учёт капитального убытка",principal-gross,0,true,"CAPITAL_LOSS_OFFSET","OTHER",id);
                w.budget=w.budget.postTagged(source+"-loss","Реализованный убыток",0,principal-gross,false,"INVESTMENT_LOSS","OTHER",id);
            }
        }else w.budget=w.budget.postTagged(source,"Продажа: "+asset.title(),gross,0,true,"ASSET_SALE","PROPERTY",id);
        var updated=new ArrayList<AdultState.Debt>();
        for(var d:w.debts){
            if(!linked(w,d,asset)){updated.add(d);continue;}
            if(d.principal()>0)w.budget=w.budget.postTagged(source+"-debt-"+d.id(),"Погашение залога при продаже",0,d.principal(),true,"DEBT_PRINCIPAL","OTHER",d.id());
            updated.add(new AdultState.Debt(d.id(),d.kind(),0,d.annualBasisPoints(),d.monthlyPayment(),d.beganMonth(),d.missedPayments(),asset.id()));
        }
        w.debts.clear();w.debts.addAll(updated);w.assets.removeIf(a->a.id().equals(id));
        if(asset.purchaseId()!=null){
            var purchase=w.budget.purchases().stream().filter(p->p.id().toString().equals(asset.purchaseId())).findFirst().orElseThrow();
            w.budget=w.budget.sold(purchase.id(),w.month());if(purchase.itemId().equals("house"))w.housing="RENTED_HOME";
        }
        w.management.enabled=true;w.management.sales.add(new BudgetState.Sale(id,asset.title(),w.month(),gross,debt,gross-debt));
        return BudgetOutcome.applied("Актив продан; связанный долг погашен, содержание прекращено",w.month(),null,Map.of("proceeds",money(gross),"debtRepaid",money(debt),"net",money(gross-debt)));
    }
}
