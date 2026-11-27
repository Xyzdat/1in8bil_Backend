package com.life.life_backend.engine.adult;

import java.util.*;

/** Authored gameplay amounts in cost units, not national salaries or market investment advice. */
final class BudgetCatalog {
    record Job(String id,String title,int payUnits,int hours,int qualification,int experience,String education,boolean secondary) { }
    record Business(String id,String title,int investmentUnits,int monthlyCostUnits,int netUnits,int risk) { }
    record Strategy(String id,String title,String description,String risk,boolean implemented,int annualBasisPoints) { }
    static final List<Job> JOBS=List.of(
            new Job("warehouse","Оператор склада",18,40,0,0,"SECONDARY",false),new Job("cook-assistant","Помощник повара",16,40,0,0,"SECONDARY",false),
            new Job("support","Специалист поддержки",20,40,0,0,"SECONDARY",false),new Job("repair","Мастер ремонта",24,40,1,12,"SECONDARY",false),
            new Job("analyst","Аналитик",32,40,2,12,"HIGHER",false),new Job("part-time-office","Офисный помощник",12,24,0,0,"SECONDARY",false),
            new Job("weekend-help","Помощник по выходным",5,8,0,0,"SECONDARY",true),new Job("evening-support","Вечерняя поддержка",8,12,0,6,"SECONDARY",true),
            new Job("tutoring","Репетитор",10,8,2,12,"HIGHER",true));
    static final List<Business> BUSINESSES=List.of(new Business("workshop","Мастерская",20,6,12,50),
            new Business("repair-service","Сервис ремонта",30,8,15,55),new Business("small-shop","Небольшой магазин",40,12,18,65));
    static final List<Strategy> STRATEGIES=List.of(
            new Strategy("cash","Без инвестирования","На весь положительный денежный остаток доход не начисляется.","NONE",true,0),
            new Strategy("deposit","Вклад","Игровые 1% годовых, ежемесячное начисление на положительный остаток. Это номинальная игровая ставка.","LOW",true,100),
            new Strategy("bonds","Облигации","Оценка стоимости и купоны пока не моделируются.","LOW",false,0),
            new Strategy("balanced-fund","Сбалансированный фонд","Портфельная стратегия пока не реализована.","MEDIUM",false,0),
            new Strategy("equity-fund","Фонд акций","Рыночные колебания фонда пока не реализованы.","HIGH",false,0),
            new Strategy("world-index","Мировой индекс","Индексная стратегия пока не реализована.","HIGH",false,0),
            new Strategy("trading","Самостоятельная торговля","Сделки и торговые риски пока не реализованы.","VERY_HIGH",false,0));
    static Job job(String id){return JOBS.stream().filter(x->x.id().equals(id)).findFirst().orElse(null);}
    static Business business(String id){return BUSINESSES.stream().filter(x->x.id().equals(id)).findFirst().orElse(null);}
    static Strategy strategy(String id){return STRATEGIES.stream().filter(x->x.id().equals(id)).findFirst().orElse(null);}
    static int effectiveRate(AdultWorld w){return "cash".equals(w.management.strategy)?0:100;}
}
