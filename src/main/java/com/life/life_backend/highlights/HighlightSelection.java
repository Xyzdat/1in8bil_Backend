package com.life.life_backend.highlights;

import java.util.*;
import static com.life.life_backend.highlights.LifeHighlights.*;

/** Select whole decision chains, then tell them in chronological order. Never pad with routine events. */
final class HighlightSelection {
    static final int LIMIT=12;
    static List<String> select(List<Candidate> candidates) {
        var pool=new ArrayList<>(candidates.stream().filter(c->c.score()>=16&&!c.decisions().isEmpty()).toList());
        var selected=new ArrayList<Candidate>();
        var templates=new HashMap<String,Integer>();var domains=new HashSet<String>();var stages=new HashSet<Integer>();
        while(selected.size()<LIMIT&&!pool.isEmpty()) {
            pool.removeIf(c->templates.getOrDefault(c.templateId(),0)>=2);
            if(pool.isEmpty())break;
            pool.sort(Comparator.<Candidate>comparingInt(c->c.score()+(domains.contains(c.domain())?0:10)
                    +(stages.contains(stage(c.firstMonth()))?0:10)-templates.getOrDefault(c.templateId(),0)*15)
                    .reversed().thenComparingInt(Candidate::firstMonth).thenComparing(Candidate::id));
            var choice=pool.removeFirst();selected.add(choice);templates.merge(choice.templateId(),1,Integer::sum);
            domains.add(choice.domain());stages.add(stage(choice.firstMonth()));
        }
        return selected.stream().sorted(Comparator.comparingInt(Candidate::firstMonth).thenComparing(Candidate::id))
                .map(Candidate::id).toList();
    }
    private static int stage(int month){return month<216?0:month<360?1:month<600?2:month<780?3:4;}
}
