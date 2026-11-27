package com.life.life_backend.engine.adult;

import java.math.BigDecimal;
import java.util.Map;

/** Stored with the action even for a declined random outcome; a retry cannot redraw it. */
public record BudgetOutcome(String status,String code,String message,int month,String currency,Integer periodMonths,Map<String,BigDecimal> amounts) {
    public BudgetOutcome { amounts=Map.copyOf(amounts); }
    static BudgetOutcome applied(String message,int month,Integer period,Map<String,BigDecimal> amounts){return new BudgetOutcome("APPLIED","OK",message,month,"RUB",period,amounts);}
    static BudgetOutcome declined(String message,int month){return new BudgetOutcome("DECLINED","APPLICATION_DECLINED",message,month,"RUB",null,Map.of());}
}
