package com.life.life_backend.highlights;

import com.life.life_backend.engine.adult.AdultState;
import java.util.*;
import static com.life.life_backend.highlights.LifeHighlights.*;

/** Tracks only entities actually created/changed by a linked action; unrelated life events earn no credit. */
final class HighlightAnchors {
    static void capture(CandidateWork c,EventLink link,String eventId,HighlightFrame before,HighlightFrame after) {
        int month=after.month();
        if("purchase".equals(link.action())&&after.budget()!=null)for(var purchase:after.budget().purchases())
            if(link.sourceId().equals("purchase-"+purchase.id())) {
                if(purchase.location()!=null)fact(c,"RESIDENCE_CHANGED",purchase.id().toString(),month,"Переезд: "+purchase.location().country()+", "+purchase.location().city(),eventId);
                else if(purchase.categoryId().equals("property"))fact(c,"PROPERTY_ACQUIRED",purchase.id().toString(),month,"Приобретено: "+purchase.title(),eventId);
                else if(purchase.categoryId().equals("pets"))fact(c,"PET_ADOPTED",purchase.id().toString(),month,"В доме появился питомец: "+purchase.petName(),eventId);
                if(purchase.completionMonth()!=null)fact(c,"TRAINING_STARTED",purchase.id().toString(),month,"Начат курс: "+purchase.title(),eventId);
            }
        if("training_completed".equals(link.action()))fact(c,"TRAINING_COMPLETED",link.sourceId(),month,"Купленный курс завершён",eventId);
        var next=after.adult();if(next==null)return;var old=before.adult();
        String action=link.action()==null?"":link.action();
        if(Set.of("apply_job","apply_part_time","job_referral","business").contains(action))for(var job:next.career().history())
            if(job.beganMonth()==month&&(old==null||old.career().history().stream().noneMatch(j->j.id().equals(job.id())))) {
                c.anchor("JOB",job.id(),month);fact(c,"JOB_STARTED",job.id(),month,"Началась работа: "+job.occupation(),eventId);
            }
        if(Set.of("study","scholarship").contains(action)&&next.education().startedMonth()!=null
                &&next.education().startedMonth()==month&&(old==null||!Objects.equals(old.education().startedMonth(),next.education().startedMonth()))) {
            String id=String.valueOf(next.education().startedMonth());c.anchor("EDUCATION",id,month);
            fact(c,"EDUCATION_STARTED",id,month,"Началась программа обучения",eventId);
        }
        if(Set.of("date","marry","cohabit","separate","end_contact").contains(action)&&next.partnership()!=null) {
            var p=next.partnership();String id=partnershipId(p);
            if(action.equals("date")&&(old==null||old.partnership()==null||!partnershipId(old.partnership()).equals(id))) {
                c.anchor("PARTNERSHIP",id,month);fact(c,"PARTNERSHIP_STARTED",id,month,"Началась общая история отношений",eventId);
            }
            if(action.equals("marry")&&p.status().equals("MARRIED")&&(old==null||old.partnership()==null||!old.partnership().status().equals("MARRIED"))) {
                c.anchor("PARTNERSHIP",id,month);fact(c,"MARRIED",id,month,"Вы вступили в брак",eventId);
            }
            if(Set.of("SEPARATED","DIVORCED").contains(p.status())&&old!=null&&old.partnership()!=null&&!old.partnership().status().equals(p.status()))
                fact(c,"PARTNERSHIP_ENDED",id,month,p.status().equals("DIVORCED")?"Брак завершился разводом":"Вы расстались",eventId);
        }
        if(Set.of("birth","adoption_review").contains(action))for(var person:after.simulation().family())
            if(person.role().equals("child")&&before.simulation().family().stream().noneMatch(p->p.id().equals(person.id()))) {
                c.anchor("CHILD",person.id(),month);fact(c,"CHILD_JOINED",person.id(),month,"В семье появился ребёнок: "+person.name(),eventId);
            }
        if(Set.of("diagnose","self_care","injury_care","injury_rest").contains(action)) {
            // The initial symptoms/injury may have been generated before the player chose treatment.
            var story=next.stories().stream().filter(s->s.id().equals(link.sourceId())).findFirst();
            String condition=story.map(s->s.variables().get("condition")).orElse(null);
            if(condition!=null) {
                c.anchor("HEALTH",condition,month);fact(c,"HEALTH_EPISODE",condition,month,"Принято решение о восстановлении здоровья",eventId);
            }
        }
        if(Set.of("buy_home","mortgage","buy_vehicle","invest").contains(action))for(var asset:next.assets())
            if(asset.acquiredMonth()==month&&(old==null||old.assets().stream().noneMatch(a->a.id().equals(asset.id())))) {
                c.anchor("ASSET",asset.id(),month);fact(c,"PROPERTY_ACQUIRED",asset.id(),month,"Приобретён актив: "+asset.title(),eventId);
            }
        if("qualify".equals(action)&&old!=null&&next.career().qualification()>old.career().qualification())
            fact(c,"QUALIFICATION_GAINED",link.sourceId(),month,"Повышена профессиональная квалификация",eventId);
        if("promotion_review".equals(action)&&old!=null&&next.career().monthlyPay()>old.career().monthlyPay())
            fact(c,"PROMOTED",link.sourceId(),month,"Повышение состоялось, зарплата выросла",eventId);
        if(Set.of("quit","bankrupt","retire").contains(action)&&old!=null&&old.career().history().stream().anyMatch(j->j.endedMonth()==null)
                &&next.career().history().stream().noneMatch(j->j.endedMonth()==null))
            fact(c,action.equals("retire")?"RETIRED":"JOB_ENDED",link.sourceId(),month,action.equals("retire")?"Принято решение выйти на пенсию":"Работа завершена по принятому решению",eventId);
        if(Set.of("mortgage","loan").contains(action))for(var debt:next.debts())
            if(debt.beganMonth()==month&&(old==null||old.debts().stream().noneMatch(d->d.id().equals(debt.id())))) {
                c.anchor("DEBT",debt.id(),month);fact(c,"DEBT_TAKEN",debt.id(),month,"Возникло обязательство по выплате займа",eventId);
            }
        if(Set.of("end_contact","contact_response").contains(action))for(String personId:link.participantIds()) {
            var contact=next.contacts().get(personId);var previous=old==null?null:old.contacts().get(personId);
            if(contact!=null&&(previous==null||previous.status()!=contact.status())) {
                if(contact.status()==com.life.life_backend.engine.adult.ContactState.Status.NO_CONTACT)
                    fact(c,"CONTACT_ENDED",personId,month,"Общение было прекращено",eventId);
                if(contact.status()==com.life.life_backend.engine.adult.ContactState.Status.IN_TOUCH)
                    fact(c,"CONTACT_RESTORED",personId,month,"Получен ответ и восстановлено общение",eventId);
            }
        }
    }

    static void observe(CandidateWork c,HighlightFrame frame) {
        var adult=frame.adult();if(adult==null)return;int month=frame.month();
        for(int i=0;i<c.anchors.size();i++) {
            var anchor=c.anchors.get(i);if(anchor.endedMonth()!=null)continue;
            Integer end=null;
            switch(anchor.kind()) {
                case "JOB" -> {
                    var job=adult.career().history().stream().filter(j->j.id().equals(anchor.id())).findFirst();
                    if(job.isPresent()&&job.get().endedMonth()!=null) {
                        end=job.get().endedMonth();
                        if(!"Жизнь завершена".equals(job.get().endReason()))
                            fact(c,"JOB_ENDED",anchor.id(),end,"Эта работа завершилась: "+job.get().endReason(),null);
                    }
                }
                case "EDUCATION" -> {
                    var e=adult.education();
                    if(Objects.equals(anchor.id(),String.valueOf(e.startedMonth()))) {
                        if(e.status().equals("COMPLETED")) {end=month;fact(c,"EDUCATION_COMPLETED",anchor.id(),month,"Обучение завершено, получена квалификация",null);}
                        if(e.status().equals("INTERRUPTED")) {end=month;fact(c,"EDUCATION_INTERRUPTED",anchor.id(),month,"Обучение было прервано",null);}
                    } else end=month;
                }
                case "PARTNERSHIP" -> {
                    var p=adult.partnership();
                    if(p==null||!partnershipId(p).equals(anchor.id())||Set.of("SEPARATED","DIVORCED","WIDOWED").contains(p.status())) {
                        end=month;fact(c,"PARTNERSHIP_ENDED",anchor.id(),month,"Эта глава отношений завершилась",null);
                    }
                }
                case "HEALTH" -> {
                    var d=adult.diseases().stream().filter(x->x.id().equals(anchor.id())).findFirst();
                    if(d.isPresent()) {
                        if(d.get().chronic())factOnce(c,"CHRONIC_CONDITION",anchor.id(),month,"Состояние потребовало длительного наблюдения");
                        if(d.get().endedMonth()!=null) {end=d.get().endedMonth();fact(c,"RECOVERED",anchor.id(),end,"Этот эпизод здоровья завершён",null);}
                        var death=frame.simulation().death();
                        if(death!=null&&death.causeDetails()!=null&&anchor.id().equals(death.causeDetails().conditionId()))
                            fact(c,"DEATH_CONDITION",anchor.id(),month,"В причине смерти указаны осложнения этого эпизода; это не доказательство вины принятого решения",null);
                    }
                }
                case "CHILD" -> {
                    var child=frame.simulation().family().stream().filter(p->p.id().equals(anchor.id())).findFirst();
                    if(child.isPresent()) {
                        int lived=child.get().diedMonth()==null?month:child.get().diedMonth();
                        if(lived-child.get().birthMonth()>=216)factOnce(c,"CHILD_ADULT",anchor.id(),month,"Ребёнок вступил во взрослую жизнь");
                        if(!child.get().alive())end=lived;
                    }
                }
                case "ASSET" -> {if(adult.assets().stream().noneMatch(a->a.id().equals(anchor.id())))end=month;}
                case "DEBT" -> {
                    var debt=adult.debts().stream().filter(d->d.id().equals(anchor.id())).findFirst();
                    if(debt.isPresent()&&debt.get().principal()==0) {end=month;fact(c,"DEBT_CLEARED",anchor.id(),month,"Займ полностью выплачен",null);}
                }
                default -> throw new IllegalStateException("Unknown highlight anchor: "+anchor.kind());
            }
            int until=end==null?month:end;
            c.anchors.set(i,new Anchor(anchor.kind(),anchor.id(),anchor.sinceMonth(),Math.max(anchor.sinceMonth(),until),end));
        }
    }

    private static String partnershipId(AdultState.Partnership p){return p.partnerId()+":"+p.beganMonth();}
    private static void fact(CandidateWork c,String kind,String id,int month,String text,String eventId) {
        c.evidence(new Evidence(kind+":"+id,month,kind,eventId==null?"TRACKED_ENTITY":"DIRECT",text,eventId));
    }
    private static void factOnce(CandidateWork c,String kind,String id,int month,String text) {
        if(c.evidence.stream().noneMatch(e->e.id().equals(kind+":"+id)))fact(c,kind,id,month,text,null);
    }
}
