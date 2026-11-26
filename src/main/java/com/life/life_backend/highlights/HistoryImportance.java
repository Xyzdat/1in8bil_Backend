package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import java.util.HashMap;
import java.util.List;

/** Read-only presentation. Story scores follow the saved tracker, including candidates outside the slideshow. */
public final class HistoryImportance {
    private HistoryImportance() { }

    public static List<LifeState.HistoryEvent> present(List<LifeState.HistoryEvent> history, LifeHighlights highlights) {
        var scores = new HashMap<String, Integer>();
        if (highlights != null) highlights.candidates().forEach(candidate -> scores.put(candidate.id(), candidate.score()));
        return history.stream().map(event -> {
            Integer storyScore = event.link() == null ? null : scores.get(event.link().sourceId());
            int score = storyScore == null ? baseScore(event) : storyScore;
            return new LifeState.HistoryEvent(event.id(), event.age(), event.title(), event.text(), event.consequences(),
                    event.link(), score, storyScore == null ? "EVENT_RULE" : "LIFE_STORY");
        }).toList();
    }

    private static int baseScore(LifeState.HistoryEvent event) {
        if (event.id().equals("death-character")) return 100;
        if (event.id().startsWith("death-")) return 70;
        if (event.id().equals("birth")) return 50;
        if (event.link() != null && event.link().phase().equals("OFFER")) return 20;
        return 10;
    }
}
