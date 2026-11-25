package com.life.life_backend.highlights;

import java.util.*;
import static com.life.life_backend.highlights.LifeHighlights.*;

/** Versioned editorial rules: importance is not a moral judgement or a measure of happiness. */
final class HighlightScoring {
    private static final Map<String,Integer> MILESTONES=Map.ofEntries(
            Map.entry("EDUCATION_STARTED",10),Map.entry("EDUCATION_COMPLETED",24),Map.entry("EDUCATION_INTERRUPTED",8),
            Map.entry("JOB_STARTED",18),Map.entry("JOB_ENDED",8),Map.entry("PARTNERSHIP_STARTED",20),
            Map.entry("MARRIED",28),Map.entry("PARTNERSHIP_ENDED",20),Map.entry("CHILD_JOINED",32),Map.entry("CHILD_ADULT",12),
            Map.entry("HEALTH_EPISODE",8),Map.entry("CHRONIC_CONDITION",18),Map.entry("RECOVERED",12),Map.entry("DEATH_CONDITION",10),
            Map.entry("PROPERTY_ACQUIRED",18),Map.entry("DEBT_TAKEN",12),Map.entry("DEBT_CLEARED",12),
            Map.entry("CONTACT_ENDED",12),Map.entry("CONTACT_RESTORED",15),Map.entry("QUALIFICATION_GAINED",20),Map.entry("PROMOTED",18),
            Map.entry("RETIRED",22),Map.entry("RESIDENCE_CHANGED",28),Map.entry("PET_ADOPTED",14),Map.entry("TRAINING_STARTED",10),Map.entry("TRAINING_COMPLETED",20));

    static void evaluate(CandidateWork c,HighlightFrame frame) {
        var reasons=new ArrayList<Reason>();
        boolean pass=c.decisions.size()==1 && Set.of("pass","wait").contains(c.initialAction==null?"":c.initialAction);
        reasons.add(new Reason("CHOICE",pass?3:10,"Зафиксирован личный выбор"));
        int impact=c.decisions.stream().mapToInt(d->d.statChanges().values().stream().mapToInt(Math::abs).sum()
                +d.closenessChanges().values().stream().mapToInt(Math::abs).sum()).sum();
        if(impact>0)reasons.add(new Reason("IMMEDIATE_EFFECT",Math.min(12,(impact+2)/3),"Выбор изменил состояние или отношения"));
        var kinds=new TreeSet<String>();
        for(var evidence:c.evidence)if(kinds.add(evidence.kind())&&MILESTONES.containsKey(evidence.kind()))
            reasons.add(new Reason(evidence.kind(),MILESTONES.get(evidence.kind()),evidence.text()));
        long outcomes=c.evidence.stream().filter(e->Set.of("STORY_OUTCOME","ENABLED_OUTCOME").contains(e.kind())).count();
        if(outcomes>0)reasons.add(new Reason("CONFIRMED_OUTCOMES",(int)Math.min(12,outcomes*4),"Сохранены подтверждённые продолжения решения"));
        if(c.evidence.stream().anyMatch(e->Set.of("STORY_OUTCOME","ENABLED_OUTCOME").contains(e.kind())&&e.month()-c.firstMonth>=12))
            reasons.add(new Reason("LONG_TERM_OUTCOME",8,"Последствие проявилось спустя год или позже"));
        int months=c.anchors.stream().mapToInt(a->a.observedUntilMonth()-a.sinceMonth()).max().orElse(0);
        if(months>=12)reasons.add(new Reason("DURATION",Math.min(24,months/12*3),"Связанная глава жизни продолжалась "+months+" мес."));
        boolean pending=frame.state().status()!=com.life.life_backend.engine.LifeState.Status.COMPLETE
                && ((frame.adult()!=null&&frame.adult().stories().stream().anyMatch(s->s.id().equals(c.id)&&s.status().equals("ACTIVE")))
                ||frame.simulation().scheduled().stream().anyMatch(s->c.id.equals(s.sourceDecisionId())));
        if(pending)reasons.add(new Reason("AWAITING_OUTCOME",6,"Продолжение ещё ожидается; оценка предварительная"));
        int score=Math.min(100,reasons.stream().mapToInt(Reason::points).sum());
        var codes=reasons.stream().map(Reason::code).toList();
        if(score!=c.score||!codes.equals(c.reasons.stream().map(Reason::code).toList()))
            c.revisions.add(new Revision(frame.month(),c.score,score,codes));
        c.score=score;c.reasons=List.copyOf(reasons);
    }
}
