package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.life.life_backend.highlights.LifeHighlights.*;

@Component
public class HighlightTracker {
    public LifeHighlights update(LifeHighlights saved,HighlightFrame before,HighlightFrame after) {
        if(saved!=null&&saved.status().equals("FINAL"))return saved;
        // A later metadata edit must not invent an empty retrospective slideshow for an old completed life.
        if(before.state().status()==LifeState.Status.COMPLETE)return saved;
        if(saved==null)saved=LifeHighlights.start(before.month(),before.state().history().size());
        if(!saved.modelVersion().equals(VERSION))throw new IllegalStateException("Unsupported highlights version");
        var candidates=new LinkedHashMap<String,CandidateWork>();saved.candidates().forEach(c->candidates.put(c.id(),new CandidateWork(c)));
        var history=after.state().history();
        if(saved.evaluatedEventCount()>history.size())throw new IllegalStateException("Life history cannot shrink");
        for(var event:history.subList(saved.evaluatedEventCount(),history.size())) {
            var link=event.link();if(link==null||link.phase().equals("OFFER"))continue;
            CandidateWork candidate=candidates.get(link.sourceId());
            if(link.phase().equals("DECISION")) {
                if(candidate==null) {candidate=new CandidateWork(link);candidates.put(candidate.id,candidate);}
                if(candidate.decisions.stream().noneMatch(d->d.id().equals(link.decisionId())))
                    candidate.decisions.add(decision(event,link,before,after));
            }
            if(candidate!=null) {
                if(!link.phase().equals("DECISION"))candidate.evidence(new Evidence(event.id(),link.month(),
                        link.phase().equals("CANCELLED")?"CANCELLED_OUTCOME":"STORY_OUTCOME","SAME_STORY",event.text(),event.id()));
                if(Set.of("DECISION","OUTCOME").contains(link.phase()))HighlightAnchors.capture(candidate,link,event.id(),before,after);
            }
            for(String source:link.relatedSourceIds()) {
                var related=candidates.get(source);if(related==null||source.equals(link.sourceId()))continue;
                related.evidence(new Evidence("enabled:"+event.id(),link.month(),"ENABLED_OUTCOME","EXPLICIT_DEPENDENCY",event.text(),event.id()));
                if(Set.of("DECISION","OUTCOME").contains(link.phase()))HighlightAnchors.capture(related,link,event.id(),before,after);
            }
        }
        for(var candidate:candidates.values()) {HighlightAnchors.observe(candidate,after);HighlightScoring.evaluate(candidate,after);}
        boolean complete=after.state().status()==LifeState.Status.COMPLETE;
        var frozen=candidates.values().stream().map(CandidateWork::freeze).toList();
        return new LifeHighlights(VERSION,saved.trackedFromMonth(),saved.partialHistory(),after.month(),history.size(),
                complete?"FINAL":"LIVE",complete?after.month():null,frozen,HighlightSelection.select(frozen));
    }

    private Decision decision(LifeState.HistoryEvent event,EventLink link,HighlightFrame before,HighlightFrame after) {
        var question=before.state().pendingDecision();
        String title=question==null||!question.id().equals(link.decisionId())?event.title():question.title();
        String prompt=before.state().history().reversed().stream()
                .filter(h->h.id().equals(link.decisionId())||(h.link()!=null&&"OFFER".equals(h.link().phase())&&Objects.equals(h.link().decisionId(),link.decisionId())))
                .map(LifeState.HistoryEvent::text).findFirst().orElse(null);
        BigDecimal balance=null;
        if(before.budget()!=null&&after.budget()!=null&&before.month()==after.month()&&before.budget().month()==after.budget().month())
            balance=BigDecimal.valueOf(after.budget().balance()-before.budget().balance(),2);
        // Store exactly the offered label (including a displayed price), not a label from a newer catalog version.
        String label=link.offeredOptions().stream().filter(o->o.id().equals(link.optionId())).map(LifeState.Option::label).findFirst().orElse(link.optionLabel());
        return new Decision(link.decisionId(),link.month(),link.month()/12,title,prompt,link.optionId(),label,link.offeredOptions(),link.participantIds(),event.id(),event.text(),
                com.life.life_backend.engine.StatChanges.between(before.state().stats(),after.state().stats()),
                event.consequences()==null?Map.of():event.consequences().closeness(),balance);
    }
}
