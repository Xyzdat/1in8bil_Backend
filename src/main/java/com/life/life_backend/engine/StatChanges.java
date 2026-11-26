package com.life.life_backend.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/** One definition of a visible stat delta for previews and recorded decisions. */
public final class StatChanges {
    private StatChanges() { }

    public static Map<String, Integer> between(LifeState.Stats before, LifeState.Stats after) {
        var changes = new LinkedHashMap<String, Integer>();
        add(changes, "health", before.health(), after.health());
        add(changes, "happiness", before.happiness(), after.happiness());
        add(changes, "intelligence", before.intelligence(), after.intelligence());
        add(changes, "relationships", before.relationships(), after.relationships());
        add(changes, "appearance", before.appearance(), after.appearance());
        add(changes, "aura", before.aura(), after.aura());
        return Map.copyOf(changes);
    }

    private static void add(Map<String, Integer> changes, String stat, Integer before, Integer after) {
        if (before != null && after != null && !before.equals(after)) changes.put(stat, after - before);
    }
}
