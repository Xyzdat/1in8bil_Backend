package com.life.life_backend.engine.focus;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.StatChanges;
import com.life.life_backend.engine.adult.AdultState;
import com.life.life_backend.engine.local.LocalSimulation;
import java.util.*;

public final class FocusEffects {
    private FocusEffects() { }
    public static Result settle(LifeState state,LocalSimulation sim,AdultState adult,int targetMonth) {
        var selected=YearFocus.active(sim);
        if(selected==null||state.status()==LifeState.Status.COMPLETE)return new Result(state,sim);
        int elapsed=targetMonth-sim.focus().settledMonth();
        if(elapsed<=0)return new Result(state,sim);
        var fractions=new HashMap<>(sim.focus().remainders());
        var before=state.stats();var stats=new HashMap<String,Integer>();
        stats.put("health",before.health());stats.put("happiness",before.happiness());stats.put("intelligence",before.intelligence());stats.put("relationships",before.relationships());
        selected.stats().forEach((key,rate)->stats.compute(key,(k,value)->Math.clamp(value+delta(fractions,"stat:"+key,rate,elapsed),0,100)));
        var nextStats=new LifeState.Stats(stats.get("health"),stats.get("happiness"),stats.get("intelligence"),stats.get("relationships"),before.appearance(),before.aura());
        var skills=new HashMap<>(sim.skills());var skillChanges=new TreeMap<String,Integer>();
        selected.skills().forEach((skill,rate)->{
            int old=skills.getOrDefault(skill,0),value=Math.clamp(old+delta(fractions,"skill:"+skill,rate,elapsed),0,100);
            if(value!=old){skills.put(skill,value);skillChanges.put(skill,value-old);}
        });
        var closeness=new TreeMap<String,Integer>();
        var family=sim.family().stream().map(person->{
            if(!selected.roles().contains(person.role())||!YearFocus.canRelate(person,adult))return person;
            int value=Math.clamp(person.closeness()+delta(fractions,"person:"+person.id(),selected.closeness(),elapsed),0,100);
            if(value==person.closeness())return person;
            closeness.put(person.id(),value-person.closeness());
            return new LocalSimulation.Person(person.id(),person.role(),person.name(),person.birthMonth(),person.alive(),value,person.trust(),
                    person.sex(),person.occupation(),person.diedMonth(),person.death());
        }).toList();
        var updated=new FocusState(FocusState.VERSION,sim.focus().id(),sim.focus().changedMonth(),targetMonth,fractions,sim.focus().changes(),
                new FocusState.Applied(selected.id(),targetMonth,StatChanges.between(before,nextStats),skillChanges,closeness));
        if(!selected.allowed(targetMonth/12))updated=updated.select("balanced",targetMonth);
        var next=sim.evolve(sim.month(),family,skills,sim.flags(),sim.lastEvents(),sim.scheduled(),sim.pendingTemplate(),sim.death()).withFocus(updated);
        return new Result(new LifeState(state.age(),state.status(),nextStats,state.pendingDecision(),state.history()),next);
    }
    private static int delta(Map<String,Integer> fractions,String key,int annual,int months) {
        int numerator=fractions.getOrDefault(key,0)+annual*months;
        int result=numerator/12,rest=numerator%12;
        if(rest==0)fractions.remove(key);else fractions.put(key,rest);
        return result;
    }
    public record Result(LifeState state,LocalSimulation simulation) { }
}
