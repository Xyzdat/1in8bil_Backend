package com.life.life_backend.engine.adult;

import java.util.*;
import com.life.life_backend.engine.local.LocalSimulation;

final class AdultConditions {
    static final Set<String> KEYS=Set.of("study","job","promotion","work","layoff","training","business","business_crisis","burnout",
            "romance","marriage","couple","family","adoption","child_education","teen","health","injury","debt","home","vehicle",
            "investment","friend","parent","retirement","inheritance","bereavement","contact","later_life");
    static boolean test(String key,AdultWorld w) {
        return switch(key) {
            case "study" -> !w.education.level().equals("HIGHER")&&!w.education.status().equals("IN_PROGRESS");
            case "job" -> !w.employed() && RetirementPolicy.canWork(w);
            case "promotion" -> w.career.status().equals("EMPLOYED")&&w.month()-w.career.beganMonth()>=12&&w.career.qualification()<10;
            case "work" -> w.employed();
            case "layoff" -> w.career.status().equals("EMPLOYED")&&w.career.stability()<55;
            case "training" -> w.career.qualification()<10;
            case "business" -> RetirementPolicy.canWork(w)&&!w.career.status().equals("BUSINESS")&&w.budget.available()>=w.cost(20);
            case "business_crisis" -> w.career.status().equals("BUSINESS")&&(w.career.stability()<55||w.budget.balance()<0);
            case "burnout" -> w.employed()&&(w.career.satisfaction()<60||w.state.stats().health()<60);
            case "romance" -> !w.coupled()&&w.sim.family().size()<120;
            case "marriage" -> w.coupled()&&!w.partnership.status().equals("MARRIED")&&w.month()-w.partnership.beganMonth()>=6;
            case "couple" -> w.coupled()&&(w.partnership.conflict()>15||w.budget.balance()<0||w.partnership.satisfaction()<45);
            case "family" -> w.coupled()&&FamilySystem.canConceive(w,w.person(w.partnership.partnerId()))&&w.children().stream().allMatch(p -> w.month()-p.birthMonth()>=18);
            case "adoption" -> w.state.stats().health()>=40&&w.budget.balance()>w.cost(5)&&w.children().size()<5&&w.sim.family().size()<120;
            case "child_education" -> participant("student",w)!=null;
            case "teen" -> participant("teen",w)!=null;
            case "health" -> w.diseases.stream().filter(d -> d.endedMonth()==null).count()<3;
            case "injury" -> w.diseases.stream().noneMatch(d -> d.kind().equals("INJURY")&&d.endedMonth()==null);
            case "debt" -> w.budget.balance()<0||w.debts.stream().anyMatch(d -> d.principal()>0&&d.missedPayments()>0);
            case "home" -> !w.owns("house")&&w.budget.available()>=w.propertyPrice("house")/5;
            case "vehicle" -> !w.owns("motorcycle")&&w.budget.available()>=w.propertyPrice("motorcycle");
            case "investment" -> w.budget.available()>w.cost(30);
            case "friend" -> w.sim.family().size()<120||participant("friend",w)!=null;
            case "parent" -> participant("parent",w)!=null;
            case "retirement" -> w.age()>=60&&w.career.monthlyPension()==0&&w.career.experienceMonths()>0;
            case "later_life" -> w.career.status().equals("RETIRED");
            case "inheritance" -> participant("inheritance",w)!=null;
            case "bereavement" -> participant("deceased",w)!=null;
            case "contact" -> participant("distant",w)!=null;
            default -> throw new IllegalArgumentException("Unknown condition: "+key);
        };
    }
    static LocalSimulation.Person participant(String role,AdultWorld w) {
        if(role==null)return null;
        if(role.equals("partner"))return w.coupled()&&ContactSystem.canInteract(w,w.partnership.partnerId())?w.person(w.partnership.partnerId()):null;
        return w.sim.family().stream().filter(p -> Set.of("deceased","inheritance","distant").contains(role)||ContactSystem.canInteract(w,p.id())).filter(p -> switch(role) {
            case "student" -> p.alive()&&w.npcs.get(p.id()).parentIds().contains("character")&&(w.month()-p.birthMonth())/12>=16&&(w.month()-p.birthMonth())/12<=21&&!Set.of("STUDYING","QUALIFIED").contains(w.npcs.get(p.id()).education());
            case "teen" -> p.alive()&&w.npcs.get(p.id()).parentIds().contains("character")&&(w.month()-p.birthMonth())/12>=12&&(w.month()-p.birthMonth())/12<18;
            case "parent" -> p.alive()&&Set.of("mother","father").contains(p.role())&&(w.month()-p.birthMonth())/12>=65;
            case "friend" -> p.alive()&&p.role().equals("friend");
            case "inheritance" -> !p.alive()&&w.npcs.get(p.id()).estate()>0&&Set.of("mother","father","child","partner").contains(p.role());
            case "deceased" -> !p.alive()&&!w.flags.contains("grieved:"+p.id());
            case "distant" -> p.alive()&&w.contacts.containsKey(p.id())&&w.contacts.get(p.id()).status()!=ContactState.Status.IN_TOUCH
                    &&w.month()-w.contacts.get(p.id()).statusSinceMonth()>=2;
            default -> false;
        }).sorted(Comparator.comparing(LocalSimulation.Person::id)).findFirst().orElse(null);
    }
}
