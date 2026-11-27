package com.life.life_backend.engine.focus;

import com.life.life_backend.engine.adult.AdultState;
import com.life.life_backend.engine.adult.ContactState;
import com.life.life_backend.engine.local.LocalSimulation;
import java.util.*;

public final class YearFocus {
    private YearFocus() { }
    public static FocusCatalog.Definition active(LocalSimulation sim) {
        if(sim.focus()==null)return null;
        if(!FocusState.VERSION.equals(sim.focus().modelVersion()))throw new IllegalStateException("Unsupported focus model");
        var definition=FocusCatalog.get(sim.focus().id());
        if(definition==null)throw new IllegalStateException("Unknown saved focus");
        return definition.allowed(sim.month()/12)?definition:FocusCatalog.get("balanced");
    }
    public static int childhoodWeight(LocalSimulation sim,String event,int base) {
        var selected=active(sim);return selected!=null&&selected.events().contains(event)?base*FocusCatalog.multiplier():base;
    }
    public static int adultWeight(LocalSimulation sim,String template,String domain,int base) {
        var selected=active(sim);return selected!=null&&(selected.domains().contains(domain)
                ||selected.id().equals("creativity")&&template.equals("later-life"))?base*FocusCatalog.multiplier():base;
    }
    public static int dailyWeight(LocalSimulation sim,String event) {
        var selected=active(sim);return selected!=null&&selected.daily().contains(event)?FocusCatalog.multiplier():1;
    }
    public static boolean canRelate(LocalSimulation.Person person,AdultState adult) {
        var contact=adult==null?null:adult.contacts().get(person.id());
        return person.alive()&&(contact==null||contact.status()!=ContactState.Status.NO_CONTACT);
    }
    public static View view(LocalSimulation sim,AdultState adult) {
        var selected=active(sim);if(selected==null)return null;
        int age=sim.month()/12;
        var options=FocusCatalog.all().stream().map(option->{
            boolean allowed=sim.death()==null&&option.allowed(age);
            var closeness=new TreeMap<String,Integer>();
            for(var person:sim.family())if(option.roles().contains(person.role())&&canRelate(person,adult)&&option.closeness()!=0)
                closeness.put(person.id(),option.closeness());
            return new Option(option.id(),option.title(age),option.description(),allowed,
                    allowed?null:sim.death()!=null?"Жизнь завершена":age<option.minAge()?"Доступно с "+option.minAge()+" лет":"Недоступно после "+option.maxAge()+" лет",
                    new Effects(12,option.stats(),option.skills(),closeness),option.id().equals("balanced")?1:FocusCatalog.multiplier());
        }).toList();
        String context=age<3?"Раннее детство":age<6?"Дошкольный возраст":age<18?"Школьные годы":age>=65?"Пенсионный возраст":"Взрослая жизнь";
        if(adult!=null&&adult.education().status().equals("IN_PROGRESS"))context="Учёба"+(adult.education().field()==null?"":": "+adult.education().field());
        else if(adult!=null&&Set.of("EMPLOYED","PROBATION","BUSINESS").contains(adult.career().status()))context=adult.career().occupation();
        return new View(FocusState.VERSION,age,selected.id(),sim.focus().changedMonth(),sim.death()==null,context,options,sim.focus().lastApplied());
    }
    public record Effects(int periodMonths,Map<String,Integer> stats,Map<String,Integer> skills,Map<String,Integer> closeness) { }
    public record Option(String id,String title,String description,boolean available,String reason,Effects effects,int matchingEventWeightMultiplier) { }
    public record View(String modelVersion,int year,String selectedId,int changedMonth,boolean canChange,String context,
                       List<Option> options,FocusState.Applied lastApplied) { }
}
