package com.life.life_backend.engine.adult;

import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;

/** Modest autonomous lives: stable IDs, one-time milestones, bounded genealogy, no player cash duplication. */
final class NpcLifeSystem {
    void step(AdultWorld w,int previousMonth) {
        if(w.partnership!=null&&!w.person(w.partnership.partnerId()).alive()&&!Set.of("WIDOWED","SEPARATED","DIVORCED").contains(w.partnership.status()))
            FamilySystem.change(w,-40,0,"WIDOWED",false);
        for(var person:List.copyOf(w.sim.family())) {
            var n=w.npcs.get(person.id()); if(n==null||!person.alive())continue;
            int age=(w.month()-person.birthMonth())/12;
            var milestones=new HashSet<>(n.milestones());
            String education=n.education(), occupation=n.occupation(); long income=n.monthlyIncome();
            if(age>=18&&age<65&&income==0&&!education.equals("STUDYING")&&w.chance(.25)) {occupation="Работа по найму";income=w.cost(10+w.random.nextInt(13));}
            if(age>=65&&milestones.add("npc-retired")) {occupation="Пенсионер";income=w.cost(5);}
            long estate=n.estate()+Math.max(0,income/5)*(w.month()-previousMonth);
            boolean descendant=Set.of("child","grandchild","great-grandchild").contains(person.role());
            if(descendant) {
                milestone(w,person.id(),milestones,age,1,"words","Первые слова",person.name()+" начинает произносить знакомые слова.");
                milestone(w,person.id(),milestones,age,6,"school","Школьная глава",person.name()+" начинает учиться в школе. Новые знакомые становятся частью его жизни.");
                milestone(w,person.id(),milestones,age,12,"interests","Собственные увлечения",person.name()+" выбирает занятия, которые интересны именно ему. Ваши вкусы не обязаны совпадать.");
                if(age>=18&&milestones.add("independent")) {
                    if(education.equals("NONE"))education="SECONDARY";
                    w.event("Самостоятельность",person.name()+" вступает во взрослую жизнь. Учёба, работа и отношения теперь становятся его собственным выбором.");
                }
                if(age>=22&&milestones.add("moved"))w.event("Свой дом",person.name()+" начинает вести самостоятельный быт. Вы сохраняете семейную связь на расстоянии.");
            }
            w.npcs.put(n.id(),new Npc(n.id(),n.traits(),education,occupation,income,n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds(),estate,milestones));
            if(!Objects.equals(person.occupation(),occupation))w.replacePerson(new com.life.life_backend.engine.local.LocalSimulation.Person(person.id(),person.role(),person.name(),person.birthMonth(),person.alive(),person.closeness(),person.trust(),person.sex(),occupation,person.diedMonth()));
            if(descendant&&age>=20&&age<80&&n.partnerId()==null&&w.sim.family().size()<120&&w.chance(.025)) {
                String sex=person.sex().equals("FEMALE")?"MALE":"FEMALE";
                int partnerAge=Math.max(18,age+w.random.nextInt(-4,5));
                String partner=w.newPerson("in-law",sex,w.month()-partnerAge*12,List.of());
                FamilySystem.updateNpc(w,n.id(),w.npcs.get(n.id()),"MARRIED",partner);
                FamilySystem.updateNpc(w,partner,w.npcs.get(partner),"MARRIED",n.id());
                w.event("Новая семейная связь",person.name()+" создаёт семью. В вашей общей истории появляется "+w.person(partner).name()+".");
            }
            var updated=w.npcs.get(n.id()); String partner=updated.partnerId();
            boolean partnerAlive=partner!=null&&!partner.equals("character")&&w.person(partner)!=null&&w.person(partner).alive();
            if(descendant&&age>=20&&age<=45&&partnerAlive&&w.sim.family().size()<120) {
                var other=w.person(partner); int otherAge=(w.month()-other.birthMonth())/12;
                int maternalAge=person.sex().equals("FEMALE")?age:otherAge;
                var born=w.sim.family().stream().filter(p -> w.npcs.get(p.id()).parentIds().contains(n.id())).toList();
                boolean gap=born.stream().allMatch(p -> w.month()-p.birthMonth()>=24);
                if(otherAge>=18&&maternalAge<=45&&born.size()<3&&gap&&w.chance(.016)) {
                    String role=person.role().equals("child")?"grandchild":"great-grandchild";
                    String baby=w.newPerson(role,w.chance(.5)?"FEMALE":"MALE",w.month(),List.of(n.id(),partner));
                    w.event("Новое поколение",person.name()+" становится родителем. Рождается "+w.person(baby).name()+" — новая ветвь семейной истории.");
                }
            }
            if(partner!=null&&!partner.equals("character")&&w.person(partner)!=null&&!w.person(partner).alive())
                FamilySystem.updateNpc(w,n.id(),w.npcs.get(n.id()),"WIDOWED",null);
        }
    }
    private void milestone(AdultWorld w,String id,Set<String> milestones,int age,int threshold,String key,String title,String text) {
        if(age>=threshold&&milestones.add(key)) { w.remember(key,id,text);w.event(title,text); }
    }
}
