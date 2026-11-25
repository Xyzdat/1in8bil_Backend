package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.adult.AdultState;
import com.life.life_backend.engine.local.LocalSimulation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.life.life_backend.highlights.LifeHighlights.*;

class HighlightTrackerTest {
    private final HighlightTracker tracker=new HighlightTracker();
    private final LifeState.Stats stats=new LifeState.Stats(70,70,70,70,50,0);

    @Test void graduationRaisesTheEarlierChoiceAndSnapshotsTheActualOfferedLabel() throws Exception {
        var choice=choice("study-1","university","EDUCATION",216,"study");
        var before=frame(216,List.of(),null,false,new LifeState.Decision("study-1-choice","Учиться?",choice.link().offeredOptions()));
        var started=adult(216,"IN_PROGRESS",List.of(story("study-1","university","ACTIVE")),List.of());
        var after=frame(216,List.of(choice),started,false,null);
        var tracked=tracker.update(null,before,after);
        var initial=tracked.candidates().getFirst();
        assertThat(initial.decisions().getFirst().optionLabel()).isEqualTo("Выбрать — 12000 ₽");
        assertThat(initial.decisions().getFirst().offeredOptions()).hasSize(2);
        var graduation=outcome("graduation","study-1","university",264,"graduate",List.of());
        var graduated=frame(264,List.of(choice,graduation),adult(216,"COMPLETED",List.of(story("study-1","university","COMPLETED")),List.of()),false,null);
        var result=tracker.update(tracked,after,graduated);
        assertThat(result.candidates().getFirst().score()).isGreaterThan(initial.score());
        assertThat(result.candidates().getFirst().reasons()).extracting(Reason::code).contains("EDUCATION_COMPLETED","LONG_TERM_OUTCOME");
        assertThat(result.view().slides().getFirst().month()).isEqualTo(216);
        assertThat(result.candidates().getFirst().evidence()).anyMatch(e->"graduation".equals(e.historyEventId()));
        var json=new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(result),LifeHighlights.class)).isEqualTo(result);
    }

    @Test void unfulfilledExpectationsCanLoseImportanceInsteadOfEveryEventOnlyAddingPoints() {
        var event=choice("promotion","promotion","CAREER",300,"promotion");
        var before=frame(300,List.of(),null,false,null);
        var pending=frame(300,List.of(event),adult(null,"COMPLETED",List.of(story("promotion","promotion","ACTIVE")),List.of()),false,null);
        var initial=tracker.update(null,before,pending);
        var rejected=outcome("rejected","promotion","promotion",302,"promotion_review",List.of());
        var after=frame(302,List.of(event,rejected),adult(null,"COMPLETED",List.of(story("promotion","promotion","COMPLETED")),List.of()),false,null);
        var evaluated=tracker.update(initial,pending,after);
        assertThat(evaluated.candidates().getFirst().score()).isLessThan(initial.candidates().getFirst().score());
        assertThat(evaluated.candidates().getFirst().reasons()).extracting(Reason::code).doesNotContain("AWAITING_OUTCOME","PROMOTED");
    }

    @Test void onlyExplicitDependenciesLinkAnotherStoryToTheEarlierDecision() {
        var first=choice("friend-a","friendship","SOCIAL",300,"friend_help");
        var unrelated=choice("friend-b","friendship","SOCIAL",302,"friend_help");
        var a=frame(300,List.of(first),null,false,null);
        var tracked=tracker.update(null,frame(300,List.of(),null,false,null),a);
        var b=frame(302,List.of(first,unrelated),null,false,null);tracked=tracker.update(tracked,a,b);
        var referral=outcome("referral","new-job","employment",350,"apply_job",List.of("friend-a"));
        var next=tracker.update(tracked,b,frame(350,List.of(first,unrelated,referral),null,false,null));
        assertThat(next.candidates().getFirst().evidence()).anyMatch(e->e.kind().equals("ENABLED_OUTCOME")&&e.historyEventId().equals("referral"));
        assertThat(next.candidates().get(1).evidence()).isEmpty();
    }

    @Test void aLongLivedJobMattersButUnrelatedTragedyDoesNotGetAttributedToTheChoice() {
        var event=choice("job","employment","CAREER",300,"apply_job");
        var job=new AdultState.Employment("job-id","Мастер","Компания",300,null,null);
        var after=frame(300,List.of(event),adult(null,"COMPLETED",List.of(),List.of(job)),false,null);
        var tracked=tracker.update(null,frame(300,List.of(),null,false,null),after);
        var tragedy=new LifeState.HistoryEvent("death-friend",30,"Утрата","Не связанное с выбором событие");
        var later=frame(360,List.of(event,tragedy),after.adult(),false,null);
        var result=tracker.update(tracked,after,later);
        assertThat(result.candidates().getFirst().score()).isGreaterThan(tracked.candidates().getFirst().score());
        assertThat(result.candidates().getFirst().reasons()).extracting(Reason::code).contains("DURATION");
        assertThat(result.candidates().getFirst().evidence()).noneMatch(e->"death-friend".equals(e.historyEventId()));
    }

    @Test void multipleChoicesInTheSameStoryBecomeOneSlideAndReevaluationIsIdempotent() {
        var first=choice("romance","romance","RELATIONSHIPS",240,"date");
        var a=frame(240,List.of(first),null,false,null);
        var state=tracker.update(null,frame(240,List.of(),null,false,null),a);
        var link=new EventLink("romance","romance","RELATIONSHIPS","DECISION",250,"cohabit-choice","move","Вместе","cohabit",List.of(),List.of("partner"),List.of());
        var second=new LifeState.HistoryEvent("cohabit-event",20,"Общий быт","Вы живёте вместе",null,link);
        var b=frame(250,List.of(first,second),null,false,null);var result=tracker.update(state,a,b);
        assertThat(result.candidates()).hasSize(1);assertThat(result.view().decisionCount()).isEqualTo(2);
        assertThat(tracker.update(result,b,b)).isEqualTo(result);
    }

    @Test void finalSelectionIsFrozenAndLegacyHistoryIsNeverInvented() {
        var old=frame(480,List.of(new LifeState.HistoryEvent("old",39,"Прошлое","Неизвестный выбор")),null,false,null);
        var event=choice("later","friendship","SOCIAL",480,"friend_help");
        var current=frame(480,List.of(old.state().history().getFirst(),event),null,false,null);
        var partial=tracker.update(null,old,current);
        assertThat(partial.partialHistory()).isTrue();assertThat(partial.trackedFromMonth()).isEqualTo(480);
        assertThat(partial.candidates()).hasSize(1);
        var death=new LifeState.HistoryEvent("death-character",40,"Конец","Жизнь завершилась");
        var terminal=frame(481,List.of(old.state().history().getFirst(),event,death),null,true,null);
        var result=tracker.update(partial,current,terminal);
        assertThat(result.status()).isEqualTo("FINAL");assertThat(result.finalizedAtMonth()).isEqualTo(481);
        assertThat(tracker.update(result,terminal,frame(600,List.of(),null,true,null))).isSameAs(result);
        assertThat(tracker.update(null,terminal,terminal)).isNull();
        assertThat(tracker.update(null,frame(0,List.of(),null,false,null),frame(1,List.of(death),null,true,null)).view().slides()).isEmpty();
    }

    @Test void selectionIsChronologicalLimitedAndDoesNotRepeatOneTemplateAllLife() {
        var list=new ArrayList<Candidate>();
        for(int i=0;i<40;i++) {
            var d=new Decision("d"+i,i*24,i*2,"Выбор",null,"yes","Да",List.of(),List.of(),"event"+i,"Итог",Map.of(),Map.of(),null);
            list.add(new Candidate("c"+i,"template"+(i%8),"domain"+(i%4),i*24,"act",List.of(d),List.of(),List.of(),50+i,List.of(),List.of()));
        }
        var selected=HighlightSelection.select(list);
        assertThat(selected).hasSize(12).doesNotHaveDuplicates();
        var slides=selected.stream().map(id->list.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow()).toList();
        assertThat(slides).extracting(Candidate::firstMonth).isSorted();
        assertThat(slides.stream().collect(java.util.stream.Collectors.groupingBy(Candidate::templateId,java.util.stream.Collectors.counting())).values()).allMatch(n->n<=2);
        assertThat(slides.stream().map(Candidate::domain).distinct().count()).isGreaterThan(1);
    }

    private LifeState.HistoryEvent choice(String source,String template,String domain,int month,String action) {
        var options=List.of(new LifeState.Option("yes","Выбрать — 12000 ₽"),new LifeState.Option("no","Отказаться"));
        return new LifeState.HistoryEvent(source+"-event",month/12,"Решение","Результат",null,
                new EventLink(source,template,domain,"DECISION",month,source+"-choice","yes","Выбрать",action,options,List.of(),List.of()));
    }
    private LifeState.HistoryEvent outcome(String id,String source,String template,int month,String action,List<String> related) {
        return new LifeState.HistoryEvent(id,month/12,"Продолжение","Подтверждённое последствие",null,
                new EventLink(source,template,"EDUCATION","OUTCOME",month,null,null,null,action,List.of(),List.of(),related));
    }
    private HighlightFrame frame(int month,List<LifeState.HistoryEvent> history,AdultState adult,boolean complete,LifeState.Decision pending) {
        var sim=new LocalSimulation(1,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("JPN",2026),month,List.of(),Map.of(),Set.of(),Map.of(),List.of(),null);
        return new HighlightFrame(new LifeState(month/12,complete?LifeState.Status.COMPLETE:LifeState.Status.ACTIVE,stats,pending,history),sim,adult,null);
    }
    private AdultState.Story story(String id,String template,String status) {
        return new AdultState.Story(id,template,"REVIEW",status,216,240,status.equals("ACTIVE")?null:264,List.of(),Map.of(),List.of());
    }
    private AdultState adult(Integer educationMonth,String educationStatus,List<AdultState.Story> stories,List<AdultState.Employment> jobs) {
        return new AdultState("adult-1",216,0,new AdultState.Traits(50,50,50,50),
                new AdultState.Education("HIGHER","Наука",educationMonth,null,educationStatus),
                new AdultState.Career("EMPLOYED","Мастер",null,null,null,100000,216,0,1,50,50,20,0,jobs),
                null,Map.of(),List.of(),List.of(),List.of(),stories,List.of(),null,"FAMILY_HOME",Set.of(),Map.of());
    }
}
