package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Saved evaluation, including candidates which did not make the slideshow. No runtime LLM required. */
public record LifeHighlights(String modelVersion, int trackedFromMonth, boolean partialHistory,
        int evaluatedThroughMonth, int evaluatedEventCount, String status, Integer finalizedAtMonth,
        List<Candidate> candidates, List<String> selectedIds) {
    public static final String VERSION = "life-highlights-1";
    public LifeHighlights { candidates=List.copyOf(candidates); selectedIds=List.copyOf(selectedIds); }
    public static LifeHighlights start(int month, int eventCount) {
        return new LifeHighlights(VERSION,month,month>0,month,eventCount,"LIVE",null,List.of(),List.of());
    }
    public View view() {
        var slides=selectedIds.stream().map(id->candidates.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow())
                .map(c->new Slide(c.id(),c.templateId(),c.domain(),c.firstMonth(),c.firstMonth()/12,
                        c.decisions().getFirst().title(),c.score(),c.reasons(),c.decisions(),c.evidence(),c.revisions())).toList();
        return new View(modelVersion,status,"RUB",trackedFromMonth,partialHistory,evaluatedThroughMonth,finalizedAtMonth,
                candidates.stream().mapToInt(c->c.decisions().size()).sum(),candidates.size(),slides);
    }
    public record Decision(String id,int month,int age,String title,String prompt,String optionId,String optionLabel,
            List<LifeState.Option> offeredOptions,List<String> participantIds,String historyEventId,String result,
            Map<String,Integer> statChanges,Map<String,Integer> closenessChanges,BigDecimal balanceChange) {
        public Decision { offeredOptions=List.copyOf(offeredOptions);participantIds=List.copyOf(participantIds);statChanges=Map.copyOf(statChanges);closenessChanges=Map.copyOf(closenessChanges); }
    }
    public record Evidence(String id,int month,String kind,String relation,String text,String historyEventId) { }
    public record Reason(String code,int points,String text) { }
    public record Revision(int month,int previousScore,int score,List<String> reasonCodes) {
        public Revision { reasonCodes=List.copyOf(reasonCodes); }
    }
    public record Anchor(String kind,String id,int sinceMonth,int observedUntilMonth,Integer endedMonth) { }
    public record Candidate(String id,String templateId,String domain,int firstMonth,String initialAction,
            List<Decision> decisions,List<Evidence> evidence,List<Anchor> anchors,int score,
            List<Reason> reasons,List<Revision> revisions) {
        public Candidate { decisions=List.copyOf(decisions);evidence=List.copyOf(evidence);anchors=List.copyOf(anchors);
            reasons=List.copyOf(reasons);revisions=List.copyOf(revisions); }
    }
    public record Slide(String id,String templateId,String domain,int month,int age,String title,int importance,
            List<Reason> reasons,List<Decision> decisions,List<Evidence> evidence,List<Revision> revisions) { }
    public record View(String modelVersion,String status,String currency,int trackedFromMonth,boolean partialHistory,
            int evaluatedThroughMonth,Integer finalizedAtMonth,int decisionCount,int candidateCount,List<Slide> slides) { }
}
