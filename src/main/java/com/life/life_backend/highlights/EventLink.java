package com.life.life_backend.highlights;

import com.life.life_backend.engine.LifeState;
import java.util.List;

/** Explicit authored provenance. A shared topic alone is never treated as a causal link. */
public record EventLink(String sourceId, String templateId, String domain, String phase, int month,
                        String decisionId, String optionId, String optionLabel, String action,
                        List<LifeState.Option> offeredOptions, List<String> participantIds, List<String> relatedSourceIds) {
    public EventLink {
        offeredOptions = offeredOptions == null ? List.of() : List.copyOf(offeredOptions);
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
        relatedSourceIds = relatedSourceIds == null ? List.of() : List.copyOf(relatedSourceIds);
    }
}
