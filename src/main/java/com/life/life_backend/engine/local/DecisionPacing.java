package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import java.util.*;

/** Counts offers, including unanswered offers and follow-up questions, rather than answers. */
public final class DecisionPacing {
    private DecisionPacing() { }
    public static final int MAX_PER_YEAR = 2;
    public static final int MIN_GAP_MONTHS = 4;
    public static int year(int month) { return Math.max(0, (month - 1) / 12); }

    public static boolean allowed(LifeState state, LocalSimulation sim, int month) {
        return count(state, sim, month) < MAX_PER_YEAR && month - lastOffer(state, sim) >= MIN_GAP_MONTHS;
    }

    public static int count(LifeState state, LocalSimulation sim, int month) {
        Set<String> ids = new HashSet<>();
        for (var event : state.history()) {
            var link = event.link();
            if (link != null && link.decisionId() != null && year(link.month()) == year(month)
                    && Set.of("OFFER", "DECISION").contains(link.phase()) && !"purchase".equals(link.action()))
                ids.add(link.decisionId());
            // Childhood saves from before structured event links still contain stable decision IDs.
            if (link == null && event.id().startsWith("local-") && event.id().endsWith("-answer") && event.age() == year(month))
                ids.add(event.id().substring(0, event.id().length() - 7));
        }
        if (state.pendingDecision() != null && year(sim.month()) == year(month)) ids.add(state.pendingDecision().id());
        return Math.max(ids.size(), sim.lastEvents().getOrDefault("#choice-count:" + year(month), 0));
    }

    private static int lastOffer(LifeState state, LocalSimulation sim) {
        int last = Math.max(sim.lastEvents().getOrDefault("#choice-last", -100), sim.lastEvents().getOrDefault("#decision", -100));
        for (var event : state.history()) {
            var link = event.link();
            if (link != null && link.decisionId() != null && Set.of("OFFER", "DECISION").contains(link.phase())
                    && !"purchase".equals(link.action())) last = Math.max(last, link.month());
        }
        if (state.pendingDecision() != null) last = Math.max(last, sim.month());
        return last;
    }

    /** Call before putting the new offer in history/pendingDecision. Stored inside the existing JSON checkpoint. */
    public static LocalSimulation recordOffer(LifeState state, LocalSimulation sim, int month) {
        var last = new HashMap<>(sim.lastEvents());
        last.put("#choice-count:" + year(month), count(state, sim, month) + 1);
        last.put("#choice-last", month);
        return sim.evolve(sim.month(), sim.family(), sim.skills(), sim.flags(), last, sim.scheduled(), sim.pendingTemplate(), sim.death());
    }

    public static double childhoodProbability(LifeState state, LocalSimulation sim) {
        double chance = .10;
        if (state.stats().health() < 50) chance += .18;
        if (state.stats().happiness() < 50) chance += .22;
        if (sim.family().stream().anyMatch(p -> p.alive() && p.closeness() < 40)) chance += .18;
        return chance;
    }
}
