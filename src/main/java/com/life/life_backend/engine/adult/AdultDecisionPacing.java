package com.life.life_backend.engine.adult;

/** Per-step game probabilities; no annual quota or mandatory questions. */
final class AdultDecisionPacing {
    private AdultDecisionPacing() { }
    static void restoreCounts(AdultWorld w) {
        var offers = new java.util.HashMap<Integer, java.util.Set<String>>();
        int last = w.sim.lastEvents().getOrDefault("#choice-last", -100);
        for (var s : w.stories) {
            offers.computeIfAbsent(com.life.life_backend.engine.local.DecisionPacing.year(s.beganMonth()), k -> new java.util.HashSet<>()).add(s.id() + "-CHOICE");
            last = Math.max(last, s.beganMonth());
            if (s.stage().equals("COHABIT_CHOICE")) {
                offers.computeIfAbsent(com.life.life_backend.engine.local.DecisionPacing.year(s.dueMonth()), k -> new java.util.HashSet<>()).add(s.id() + "-COHABIT_CHOICE");
                last = Math.max(last, s.dueMonth());
            }
        }
        for (var e : w.state.history()) {
            var link = e.link();
            if (link != null && link.decisionId() != null && java.util.Set.of("OFFER", "DECISION").contains(link.phase()) && !"purchase".equals(link.action())) {
                offers.computeIfAbsent(com.life.life_backend.engine.local.DecisionPacing.year(link.month()), k -> new java.util.HashSet<>()).add(link.decisionId());
                last = Math.max(last, link.month());
            }
        }
        var ledger = new java.util.HashMap<>(w.sim.lastEvents());
        offers.forEach((year, ids) -> ledger.merge("#choice-count:" + year, ids.size(), Math::max));
        ledger.put("#choice-last", last);
        w.sim = w.sim.evolve(w.month(), w.sim.family(), w.sim.skills(), w.sim.flags(), ledger, w.sim.scheduled(), w.sim.pendingTemplate(), w.sim.death());
    }
    static double probability(AdultWorld w) {
        double chance = .06;
        if (!w.employed() && RetirementPolicy.canWork(w) && !w.education.status().equals("IN_PROGRESS")) chance += .40;
        if (w.budget.balance() < 0 || w.debts.stream().anyMatch(d -> d.principal() > 0 && d.missedPayments() > 0)) chance += .22;
        if (w.state.stats().health() < 50) chance += .22;
        if (w.state.stats().happiness() < 45) chance += .15;
        if (w.coupled() && (w.partnership.conflict() > 25 || w.partnership.satisfaction() < 40)) chance += .18;
        if (w.employed() && (w.career.satisfaction() < 40 || w.career.stability() < 40)) chance += .15;
        return Math.min(.85, chance);
    }
}
