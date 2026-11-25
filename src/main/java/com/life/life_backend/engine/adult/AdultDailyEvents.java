package com.life.life_backend.engine.adult;

import java.util.*;

final class AdultDailyEvents {
    private record Moment(String id,String title,String text,int health,int happiness,int intelligence,String participant){}
    void next(AdultWorld w) {
        var options=new ArrayList<Moment>();
        if(w.employed()) {
            options.add(new Moment("work-routine","Освоенная задача","В работе «"+w.career.occupation()+"» знакомая задача даётся увереннее. Накопленный опыт помогает замечать детали.",0,1,1,null));
            options.add(new Moment("work-boundary","Конец рабочего дня","После работы ты оставляешь часть задач до завтра. Короткая передышка помогает восстановить силы.",1,1,0,null));
        }
        if(w.education.status().equals("IN_PROGRESS"))options.add(new Moment("study-practice","Практика на учёбе","Ты применяешь материал программы к небольшой практической задаче. Отдельные знания складываются в понятную картину.",0,1,1,null));
        if(w.budget.balance()<0)options.add(new Moment("budget-pressure","Обязательства на бумаге","Ты сверяешь накопившиеся расходы. Теперь лучше видно, какие обязательства требуют внимания в первую очередь.",0,-1,1,null));
        else options.add(new Moment("budget-order","Навести порядок в расходах","Ты сверяешь оплаченные счета с оставшимися средствами. Понимание своего бюджета делает ближайшие планы спокойнее.",0,1,0,null));
        for(var p:w.sim.family()) if(ContactSystem.canInteract(w,p.id())&&p.closeness()>=40) {
            options.add(new Moment("conversation-"+p.id(),"Время для разговора",p.name()+" делится тем, что произошло за последнее время. Ты слушаешь и замечаешь, как меняется его жизнь.",0,1,0,p.id()));
            if(options.size()>10)break;
        }
        options.add(new Moment("rest","День без спешки",w.state.stats().health()<50?"Ты оставляешь больше времени для отдыха, учитывая самочувствие. Сегодня восстановление важнее скорости.":"Ты находишь время для спокойных дел. Отдых помогает вернуться к привычному ритму.",1,1,0,null));
        options.add(new Moment("reading","Новый взгляд","Ты читаешь о незнакомом занятии и пробуешь разобраться в его основах. Это ещё не новая профессия, но интерес уже появился.",0,1,1,null));
        options.add(new Moment("walk","Знакомый маршрут",w.location.city()==null?"Прогулка по знакомым улицам помогает переключиться после повседневных забот.":"Ты выбираешь спокойный маршрут по городу "+w.location.city()+". На время повседневные заботы отходят на второй план.",1,1,0,null));
        options.add(new Moment("hobby","Занятие для себя","Ты возвращаешься к делу, которое нравится без внешней оценки. Небольшой результат приносит удовлетворение.",0,2,0,null));
        options.add(new Moment("home","Привычное пространство","Ты приводишь в порядок вещи дома. Повседневное пространство становится удобнее.",0,1,0,null));
        options.add(new Moment("reflection","Пересмотреть планы","Ты вспоминаешь недавние решения и отмечаешь, что хочется сохранить, а что — изменить. Прожитый опыт помогает точнее понимать себя.",0,1,1,null));
        options.add(new Moment("exercise","Посильная нагрузка","Ты выбираешь движение, которое подходит сегодняшнему самочувствию. Регулярность оказывается важнее рекордов.",1,0,0,null));
        int oldest=options.stream().mapToInt(o -> w.sim.lastEvents().getOrDefault("adult-daily:"+o.id(),-10000)).min().orElseThrow();
        var candidates=options.stream().filter(o -> w.sim.lastEvents().getOrDefault("adult-daily:"+o.id(),-10000)==oldest).toList();
        var chosen=candidates.get(w.random.nextInt(candidates.size())); var before=w.state.stats();var old=w.sim;
        w.stats(chosen.health(),chosen.happiness(),chosen.intelligence(),0);
        if(chosen.participant()!=null)w.relationship(chosen.participant(),1,1);
        var last=new HashMap<>(w.sim.lastEvents());last.put("adult-daily:"+chosen.id(),w.month());
        w.sim=w.sim.evolve(w.month(),w.sim.family(),w.sim.skills(),w.sim.flags(),last,w.sim.scheduled(),null,w.sim.death());
        w.event(chosen.title(),chosen.text(),before,old);
    }
}
