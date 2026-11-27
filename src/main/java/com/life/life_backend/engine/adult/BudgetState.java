package com.life.life_backend.engine.adult;

import java.util.*;

/** Employment conditions/history, never a second wallet. */
public record BudgetState(String modelVersion,Integer mainHours,SideJob secondary,List<SideJob> secondaryHistory,
                          Business business,String strategy,Integer lastRaiseMonth,List<Sale> sales) {
    public static final String VERSION="budget-management-1";
    public BudgetState { secondaryHistory=List.copyOf(secondaryHistory);sales=List.copyOf(sales); }
    public record SideJob(String id,String offerId,String title,long basePay,int hours,int beganMonth,int experienceMonths,Integer endedMonth) { }
    public record Business(String id,String catalogId,String title,long monthlyCost,int beganMonth) { }
    public record Sale(String assetId,String title,int month,long proceeds,long debtRepaid,long net) { }
    static final class Work {
        boolean enabled;Integer mainHours;SideJob secondary;List<SideJob> history;Business business;String strategy;Integer lastRaise;List<Sale> sales;
        Work(BudgetState state){enabled=state!=null;mainHours=state==null?null:state.mainHours();secondary=state==null?null:state.secondary();
            history=new ArrayList<>(state==null?List.of():state.secondaryHistory());business=state==null?null:state.business();
            strategy=state==null?null:state.strategy();lastRaise=state==null?null:state.lastRaiseMonth();sales=new ArrayList<>(state==null?List.of():state.sales());}
        BudgetState freeze(){return !enabled?null:new BudgetState(VERSION,mainHours,secondary,history,business,strategy,lastRaise,sales);}
    }
}
