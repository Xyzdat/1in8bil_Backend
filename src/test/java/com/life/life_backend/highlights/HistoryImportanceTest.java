package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HistoryImportanceTest {
    @Test void usesAllTrackedStoriesAndRevisesEarlierEventsWithoutEditingHistory() {
        var link = new EventLink("story","template","RELATIONSHIPS","OFFER",38,"question",null,null,null,List.of(),List.of(),List.of());
        var event = new LifeState.HistoryEvent("question",3,"Вопрос","Текст",null,link);
        var history = List.of(event);
        assertThat(HistoryImportance.present(history,tracked(12)).getFirst().importance()).isEqualTo(12);
        var revised = HistoryImportance.present(history,tracked(81)).getFirst();
        assertThat(revised.importance()).isEqualTo(81);
        assertThat(revised.importanceSource()).isEqualTo("LIFE_STORY");
        assertThat(event.importance()).isNull();
        assertThat(revised.id()).isEqualTo(event.id());
        assertThat(tracked(81).selectedIds()).isEmpty();
    }

    @Test void knownMilestonesAndRoutineEventsHaveExplicitBaseScores() {
        var history = List.of("birth","local-2-play","death-mother","death-character").stream()
                .map(id -> new LifeState.HistoryEvent(id,0,"Название не влияет на оценку","Текст")).toList();
        var shown = HistoryImportance.present(history,null);
        assertThat(shown).extracting(LifeState.HistoryEvent::importance).containsExactly(50,10,70,100);
        assertThat(shown).allMatch(e -> e.importanceSource().equals("EVENT_RULE"));
    }

    private LifeHighlights tracked(int score) {
        var candidate = new LifeHighlights.Candidate("story","template","RELATIONSHIPS",38,"draw",List.of(),List.of(),List.of(),score,List.of(),List.of());
        return new LifeHighlights(LifeHighlights.VERSION,0,false,50,1,"LIVE",null,List.of(candidate),List.of());
    }
}
