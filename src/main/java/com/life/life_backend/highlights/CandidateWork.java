package com.life.life_backend.highlights;

import java.util.ArrayList;
import java.util.List;
import static com.life.life_backend.highlights.LifeHighlights.*;

/** A command-local builder; the persisted records remain immutable. */
final class CandidateWork {
    final String id,template,domain,initialAction;
    final int firstMonth;
    final List<Decision> decisions=new ArrayList<>();
    final List<Evidence> evidence=new ArrayList<>();
    final List<Anchor> anchors=new ArrayList<>();
    final List<Revision> revisions=new ArrayList<>();
    int score;
    List<Reason> reasons=List.of();
    CandidateWork(EventLink link) {
        id=link.sourceId();template=link.templateId();domain=link.domain();initialAction=link.action();firstMonth=link.month();
    }
    CandidateWork(Candidate c) {
        id=c.id();template=c.templateId();domain=c.domain();initialAction=c.initialAction();firstMonth=c.firstMonth();
        decisions.addAll(c.decisions());evidence.addAll(c.evidence());anchors.addAll(c.anchors());revisions.addAll(c.revisions());
        score=c.score();reasons=c.reasons();
    }
    void evidence(Evidence value) {
        evidence.removeIf(e->e.id().equals(value.id()));evidence.add(value);
        evidence.sort(java.util.Comparator.comparingInt(Evidence::month).thenComparing(Evidence::id));
    }
    void anchor(String kind,String entityId,int month) {
        if(anchors.stream().noneMatch(a->a.kind().equals(kind)&&a.id().equals(entityId)))anchors.add(new Anchor(kind,entityId,month,month,null));
    }
    Candidate freeze() { return new Candidate(id,template,domain,firstMonth,initialAction,decisions,evidence,anchors,score,reasons,revisions); }
}
