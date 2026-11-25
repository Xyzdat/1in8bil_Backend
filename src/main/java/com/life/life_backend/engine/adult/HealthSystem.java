package com.life.life_backend.engine.adult;

import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

/** Explicit gameplay conditions. All-cause mortality is still calculated once by MortalityModel. */
final class HealthSystem {
    static final Set<String> ACTIONS=Set.of("diagnose","self_care","health_review","chronic_review","injury_care","injury_rest","rehabilitation","symptoms","accident");
    StoryProgress.Result act(String action,AdultWorld w,StoryProgress p) {
        return switch(action) {
            case "diagnose", "self_care", "injury_care", "injury_rest", "symptoms", "accident" -> {
                boolean injury=action.startsWith("injury")||action.equals("accident"); boolean treated=Set.of("diagnose","injury_care").contains(action);
                int existing=index(w,p.get("condition"));
                if(existing>=0) {
                    var old=w.diseases.get(existing);
                    w.diseases.set(existing,new Disease(old.id(),old.kind(),old.onsetMonth(),old.severity(),old.stage(),treated?"SUPERVISED":"REST",w.month()+6,null,false,old.workCapacity()));
                    yield next(treated?"Ты начинаешь восстановление под наблюдением. Расходы на него учитываются в бюджете.":"Ты выбираешь отдых и самостоятельное наблюдение за состоянием.");
                }
                String id=w.id("condition"); p.put("condition",id);
                int severity=injury?25:15+w.random.nextInt(16);
                var d=new Disease(id,injury?"INJURY":"HEALTH_CONDITION",w.month(),severity,"ACTIVE",treated?"SUPERVISED":"REST",w.month()+6,null,false,100-severity);
                w.diseases.add(d); w.stats(-severity/3,-3,0,0);
                yield next(treated?"Ты обращаешься за помощью и начинаешь восстановление. Состояние временно ограничивает работоспособность и требует расходов.":"Ты сокращаешь нагрузку и наблюдаешь за самочувствием. Восстановление может занять больше времени без профессиональной помощи.");
            }
            case "health_review", "rehabilitation" -> {
                int i=index(w,p.get("condition")); if(i<0)yield end("Этот эпизод здоровья уже завершён."); var d=w.diseases.get(i);
                boolean recovered=w.chance(d.treatment().equals("SUPERVISED")?.78:.48);
                w.diseases.set(i,new Disease(d.id(),d.kind(),d.onsetMonth(),recovered?0:Math.max(5,d.severity()/2),recovered?"RECOVERED":"CHRONIC",d.treatment(),w.month()+12,recovered?w.month():null,!recovered,recovered?100:85));
                w.stats(recovered?6:1,recovered?4:-2,0,0);
                yield recovered?end("Восстановление завершилось. Ограничения этого эпизода сняты."):next("Остались длительные ограничения. Состояние требует наблюдения и учитывается в работоспособности и расходах.");
            }
            case "chronic_review" -> {
                int i=index(w,p.get("condition")); if(i<0)yield end("Эпизод уже завершён."); var d=w.diseases.get(i);
                w.diseases.set(i,new Disease(d.id(),d.kind(),d.onsetMonth(),d.severity(),"CHRONIC",d.treatment(),w.month()+12,null,true,d.workCapacity()));
                w.stats(1,1,0,0); yield end("Ты учишься учитывать состояние в повседневном распорядке. Медицинская история и текущие ограничения сохраняются.");
            }
            default -> throw new IllegalArgumentException(action);
        };
    }
    static int index(AdultWorld w,String id) { for(int i=0;i<w.diseases.size();i++) if(w.diseases.get(i).id().equals(id)&&w.diseases.get(i).endedMonth()==null)return i; return -1; }
    static int capacity(AdultWorld w) { return w.diseases.stream().filter(d -> d.endedMonth()==null).mapToInt(Disease::workCapacity).min().orElse(100); }
    static void interval(AdultWorld w) {
        // Gradual burden, bounded; not a second independent mortality roll.
        if(w.month()%12==0) {
            int burden=w.diseases.stream().filter(d -> d.endedMonth()==null).mapToInt(d -> d.treatment().equals("SUPERVISED")?0:1).sum();
            if(burden>0)w.stats(-Math.min(3,burden),0,0,0);
        }
        for(int i=0;i<w.diseases.size();i++) { var d=w.diseases.get(i);
            if(d.endedMonth()==null&&!d.chronic()&&w.month()-d.onsetMonth()>18) {
                w.diseases.set(i,new Disease(d.id(),d.kind(),d.onsetMonth(),Math.max(5,d.severity()/2),"CHRONIC",d.treatment(),w.month()+12,null,true,85));
            }
        }
    }
}
