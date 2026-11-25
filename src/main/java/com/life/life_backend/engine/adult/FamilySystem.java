package com.life.life_backend.engine.adult;

import java.util.*;
import com.life.life_backend.engine.local.LocalSimulation;
import static com.life.life_backend.engine.adult.AdultState.*;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

final class FamilySystem {
    static final Set<String> ACTIONS=Set.of("date","date_review","cohabit","marry","marriage_review","reconcile","conflict_review",
            "separate","conceive","birth","postpartum","adopt","adoption_review","child_support","child_grant","child_education",
            "listen_child","restrict_child","teen_review","care_parent","visit_parent","care_review","friend_help","friend_talk",
            "friend_review","friend_reunion","grieve","bereavement_review");
    StoryProgress.Result act(String action,AdultWorld w,StoryProgress p) {
        String id=p.actor(); var person=id==null?null:w.person(id);
        if(id!=null)ContactSystem.interact(w,id);
        return switch(action) {
            case "date" -> {
                if(w.coupled()||person==null||!person.alive()) yield end("Сейчас эти отношения не могут начаться.");
                if(!w.chance(.35+w.traits.sociability()/300d)) yield end(person.name()+" не готов к романтическим отношениям. Знакомство остаётся частью твоей истории.");
                w.partnership=new Partnership(id,"DATING",w.month(),false,60,10,List.of("Знакомство"));
                updateNpc(w,id,npc(w,id),"DATING","character");
                w.relationship(id,10,5); w.remember("dating",id,"Вы начали встречаться");
                yield next("Вы решаете встречаться и постепенно узнавать привычки друг друга.");
            }
            case "date_review" -> {
                if(!isCurrent(w,id)) yield end("Эти отношения уже завершились.");
                if(w.chance(.25)) { separate(w,id); yield end("Через несколько месяцев вы понимаете, что ожидания не совпадают, и расстаётесь."); }
                w.relationship(id,6,5); change(w,7,-3,null,null); yield next("Вы научились говорить о своих ожиданиях. Отношения становятся ближе.");
            }
            case "cohabit" -> {
                if(!isCurrent(w,id)) yield end("Совместный переезд больше не актуален.");
                change(w,4,0,"COHABITING",true); w.housing="SHARED_HOME";
                yield end("Вы решаете жить вместе. В бюджет поступает согласованная доля дохода партнёра, а общие расходы возрастают.");
            }
            case "marry" -> {
                if(!isCurrent(w,id)) yield end("Эти отношения уже завершены.");
                if(!w.chance(.25+w.partnership.satisfaction()/150d)) yield end("Партнёр пока не готов к браку. Вы сохраняете прежний формат отношений.");
                change(w,5,-2,"MARRIED",true); updateNpc(w,id,npc(w,id),"MARRIED","character");
                w.relationship(id,5,6); w.remember("marriage",id,"Вы решили создать семью");
                yield next("Вы вступаете в брак и договариваетесь о совместном быте. Семейная жизнь продолжится с общими решениями.");
            }
            case "marriage_review" -> { if(!isCurrent(w,id)) yield end("Общая глава отношений завершилась."); w.relationship(id,3,3); yield end("Вы обсуждаете, какие обязанности кому удобнее брать на себя. Привычки постепенно становятся общими."); }
            case "reconcile" -> { w.relationship(id,3,5); w.stats(0,1,0,2); if(isCurrent(w,id))change(w,8,-10,null,null); yield next("Ты выслушиваешь претензии и предлагаешь конкретный компромисс."); }
            case "conflict_review" -> {
                if(!isCurrent(w,id)) yield end("После расставания совместное обсуждение уже не требуется.");
                if(w.chance(.3+w.partnership.satisfaction()/200d)) { change(w,8,-8,null,null); w.relationship(id,5,5); yield end("Договорённости сработали. Доверие восстанавливается."); }
                change(w,-12,15,null,null); w.relationship(id,-6,-5); yield end("Старые разногласия снова проявляются. Решение о будущем отношений ещё предстоит.");
            }
            case "separate" -> { separate(w,id); yield end("Вы расстаётесь. Совместный вклад партнёра прекращается; обязательства по поддержке ваших несовершеннолетних детей сохраняются."); }
            case "conceive" -> {
                if(!isCurrent(w,id)||!canConceive(w,person)) yield end("Сейчас биологическое планирование семьи недоступно. Это не исключает других форм семьи.");
                double probability=.55*(w.state.stats().health()/100d)*(npc(w,id).health()/80d);
                if(!w.chance(probability)) yield end("В этот раз беременность не наступила. Вы можете вернуться к планированию позже.");
                String carrier=heroSex(w).equals("FEMALE")?"character":id;
                p.put("carrier",carrier); p.put("parent",id); w.flags.add("pregnancy");
                w.remember("pregnancy",id,"Вы ждёте ребёнка"); yield next("Вы узнаёте о беременности. Впереди месяцы ожидания; исход ещё не предрешён.");
            }
            case "birth" -> {
                w.flags.remove("pregnancy");
                String carrier=p.get("carrier"), parent=p.get("parent");
                if(carrier==null || (!carrier.equals("character") && !w.person(carrier).alive())) yield end("Рождение не состоялось: история ожидания завершилась после утраты.");
                if(!w.chance(.94)) { w.stats(-2,-12,0,0); yield end("Беременность прервалась. Семье нужно время, чтобы пережить потерю."); }
                String child=w.newPerson("child",w.chance(.5)?"FEMALE":"MALE",w.month(),List.of("character",parent)); p.put("child",child);
                w.flags.add("last-birth:"+w.month()); w.stats(-1,9,0,1); w.relationship(parent,5,3);
                w.remember("childbirth",child,"Рождение: "+w.person(child).name());
                yield next("В семье рождается ребёнок — "+w.person(child).name()+". У него своя история, а в бюджете появились расходы на заботу о нём.");
            }
            case "postpartum" -> { w.stats(2,2,0,0); yield end("Ты постепенно приспосабливаешься к новому распорядку и учишься замечать потребности ребёнка."); }
            case "adopt" -> { p.put("adoption",true); yield next("Ты начинаешь подготовку к усыновлению. Проверка условий семьи займёт несколько месяцев."); }
            case "adoption_review" -> {
                if(w.budget.balance()<0||w.state.stats().health()<30||w.children().size()>=5||!w.chance(.65))
                    yield end("Подготовка пока не завершилась усыновлением. Условия семьи и её возможности нужно пересмотреть.");
                List<String> parents=w.coupled()?List.of("character",w.partnership.partnerId()):List.of("character");
                int maxChildAge=Math.min(6,w.age()-18);
                if(w.coupled())maxChildAge=Math.min(maxChildAge,(w.month()-w.person(w.partnership.partnerId()).birthMonth())/12-18);
                String child=w.newPerson("child",w.chance(.5)?"FEMALE":"MALE",w.month()-w.random.nextInt(Math.max(0,maxChildAge)+1)*12,parents);
                w.stats(0,8,0,3); w.remember("adoption",child,"Ты принимаешь ребёнка в семью"); yield end("В семье появляется "+w.person(child).name()+". Вам предстоит привыкнуть друг к другу и построить доверие.");
            }
            case "child_support", "child_grant" -> {
                if(person==null||!person.alive()) yield end("Обучение уже не может начаться.");
                boolean admitted=action.equals("child_support")||w.chance(.45+npc(w,id).traits().diligence()/300d);
                if(!admitted) yield end("Конкурс на учебную помощь не пройден. Ребёнок рассматривает работу и другие программы.");
                var n=npc(w,id); w.npcs.put(id,new Npc(n.id(),n.traits(),"STUDYING",null,0,n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds(),n.estate(),n.milestones()));
                w.relationship(id,5,3); p.put("education-start",w.month()); yield next(person.name()+" начинает выбранную программу. Твоя поддержка помогает сделать первый шаг.");
            }
            case "child_education" -> {
                if(person==null||!person.alive()) yield end("Эта учебная история завершилась после утраты.");
                var n=npc(w,id); w.npcs.put(id,new Npc(n.id(),n.traits(),"QUALIFIED","Специалист",w.cost(15),n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds(),n.estate(),n.milestones()));
                yield end(person.name()+" завершает обучение и начинает искать самостоятельный профессиональный путь.");
            }
            case "listen_child" -> { w.relationship(id,7,8); w.stats(0,2,0,2); yield next("Ты спрашиваешь, что тревожит ребёнка, и договариваешься вернуться к разговору без давления."); }
            case "restrict_child" -> { w.relationship(id,-5,-7); w.stats(0,-1,0,-1); yield next("Ты вводишь строгие ограничения. Поведение меняется, но открыто говорить с тобой стало труднее."); }
            case "teen_review" -> { if(person!=null&&person.trust()>55) {w.relationship(id,4,3); yield end("Ребёнок сам возвращается к разговору. Ваши договорённости постепенно работают.");} yield end("Ребёнок пока держит переживания при себе. Для восстановления доверия потребуется время."); }
            case "care_parent", "visit_parent" -> { w.relationship(id,5,4); w.stats(0,1,0,3); p.put("care",action); yield next(action.equals("care_parent")?"Ты организуешь помощь родителю и берёшь часть расходов на себя.":"Ты находишь время для визитов и бытовой помощи. Это требует участия, даже без крупных трат."); }
            case "care_review" -> { if(person==null||!person.alive()) yield end("Родителя уже нет рядом. В памяти остаётся время, которое вы успели провести вместе."); w.relationship(id,4,4); yield end(person.name()+" благодарит за поддержку. Ты лучше понимаешь, какая помощь нужна дальше."); }
            case "friend_help", "friend_talk" -> { w.relationship(id,action.equals("friend_help")?10:5,5); w.stats(0,2,0,2); w.remember("help",id,"Ты поддержал человека в трудный период"); yield next("Ты поддерживаешь знакомого. Эта помощь остаётся частью ваших отношений."); }
            case "friend_review" -> { w.relationship(id,4,3); yield next("Дела знакомого постепенно налаживаются. Вы сохраняете связь после трудного периода."); }
            case "friend_reunion" -> {
                if(person==null||!person.alive()) yield end("До новой встречи вы не дожили вместе. В памяти сохранилась прежняя помощь.");
                w.relationship(id,5,5); if(!w.employed()) {
                    w.flags.add("job-referral");
                    w.flags.removeIf(f->f.startsWith("job-referral-source:"));
                    w.flags.add("job-referral-source:"+p.story.id());
                }
                yield end(person.name()+" вспоминает твою поддержку несколько лет назад. "+(!w.employed()?"Теперь он готов порекомендовать тебя работодателю.":"Вы находите время для новой встречи."));
            }
            case "grieve" -> { w.stats(0,2,0,1); yield next("Ты находишь время прожить утрату и вспоминаешь то, что связывало вас."); }
            case "bereavement_review" -> { var memory=w.memories.stream().filter(m -> Objects.equals(id,m.personId())).findFirst(); w.stats(0,3,0,0); yield end(memory.map(m -> "В памяти возвращается: "+m.text()+". Эта часть общей истории остаётся с тобой.").orElse("Память о близком человеке постепенно становится спокойнее, хотя утрата остаётся частью жизни.")); }
            default -> throw new IllegalArgumentException(action);
        };
    }
    static String heroSex(AdultWorld w) { return w.sim.birthFacts()==null?"MALE":w.sim.birthFacts().sex(); }
    static boolean isCurrent(AdultWorld w,String id) { return w.coupled() && w.partnership.partnerId().equals(id); }
    static Npc npc(AdultWorld w,String id) { return w.npcs.get(id); }
    static boolean canConceive(AdultWorld w,LocalSimulation.Person partner) {
        if(partner==null||!partner.alive()||heroSex(w).equals(partner.sex())||w.flags.contains("pregnancy")||w.children().size()>=5||w.sim.family().size()>=120) return false;
        int pa=(w.month()-partner.birthMonth())/12;
        int motherAge=heroSex(w).equals("FEMALE")?w.age():pa;
        return w.age()>=18&&pa>=18&&motherAge<=45&&w.state.stats().health()>=25;
    }
    static void change(AdultWorld w,int satisfaction,int conflict,String status,Boolean cohabiting) {
        var p=w.partnership; var milestones=new ArrayList<>(p.milestones()); if(status!=null)milestones.add(status+" @"+w.month());
        w.partnership=new Partnership(p.partnerId(),status==null?p.status():status,p.beganMonth(),cohabiting==null?p.cohabiting():cohabiting,
                AdultWorld.clamp(p.satisfaction()+satisfaction),AdultWorld.clamp(p.conflict()+conflict),milestones);
    }
    static void separate(AdultWorld w,String id) {
        if(!isCurrent(w,id))return;
        boolean married=w.partnership.status().equals("MARRIED");
        change(w,-30,20,married?"DIVORCED":"SEPARATED",false); w.relationship(id,-15,-10); w.stats(0,-8,0,0);
        updateNpc(w,id,npc(w,id),"SINGLE",null);
        if(married) for(int i=0;i<w.assets.size();i++) { var a=w.assets.get(i);
            if(a.acquiredMonth()>=w.partnership.beganMonth()&&a.ownershipPercent()==100)
                w.assets.set(i,new Asset(a.id(),a.kind(),a.title(),a.purchasePrice(),a.value(),a.acquiredMonth(),50,a.purchaseId()));
        }
        w.remember("separation",id,"Вы расстались");
    }
    static void updateNpc(AdultWorld w,String id,Npc n,String status,String partner) {
        w.npcs.put(id,new Npc(n.id(),n.traits(),n.education(),n.occupation(),n.monthlyIncome(),n.health(),status,partner,n.countryIso2(),n.parentIds(),n.estate(),n.milestones()));
    }
}
