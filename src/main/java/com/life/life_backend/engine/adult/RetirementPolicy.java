package com.life.life_backend.engine.adult;

import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;

/** Authored game rule. Country-specific pension legislation has not been imported. */
final class RetirementPolicy {
    static final int AGE = 65;
    private static final Set<String> WORK_CONDITIONS = Set.of("job", "promotion", "work", "layoff", "business", "business_crisis", "burnout", "retirement");
    static boolean canWork(AdultWorld w) { return w.age() < AGE && w.career.monthlyPension() == 0 && !w.career.status().equals("RETIRED"); }

    static void retire(AdultWorld w) {
        long pension = w.career.monthlyPension() > 0 ? w.career.monthlyPension()
                : w.cost(5) + Math.min(w.cost(15), w.career.experienceMonths() * w.cost(1) / 120);
        CareerSystem.leave(w, "Выход на пенсию");
        var c = w.career;
        w.career = new Career("RETIRED", null, null, null, null, 0, w.month(), c.experienceMonths(),
                c.qualification(), 60, 100, 0, pension, c.history());
    }

    static void apply(AdultWorld w, AdultStoryCatalog catalog) {
        if (canWork(w)) return;
        if (!w.career.status().equals("RETIRED") || w.career.monthlyPension() == 0) {
            retire(w);
            w.event("Выход на пенсию", "Рабочий этап завершён. Начисляется игровая пенсия; появляется время для семьи, отдыха и новых занятий.", w.state.stats(), w.sim);
        }
        for (var s : List.copyOf(w.stories)) {
            if (!s.status().equals("ACTIVE") || !WORK_CONDITIONS.contains(catalog.get(s.template()).condition())) continue;
            // Preserve the automatic follow-up of an already chosen early retirement.
            if (s.template().equals("retirement") && !s.stage().equals("CHOICE")) continue;
            w.stories.remove(s);
            w.stories.add(new Story(s.id(), s.template(), s.stage(), "CANCELLED", s.beganMonth(), s.dueMonth(), w.month(), s.participants(), s.variables(), s.visitedStages()));
            if (w.pending != null && w.pending.storyId().equals(s.id())) { w.pending = null; w.decision(null); }
            w.event("Рабочие планы завершены", "История «" + catalog.get(s.template()).title() + "» закрыта после выхода на пенсию.", w.state.stats(), w.sim,
                    new com.life.life_backend.highlights.EventLink(s.id(), s.template(), catalog.get(s.template()).domain(), "CANCELLED", w.month(),
                            null, null, null, null, List.of(), s.participants(), List.of()));
        }
    }
}
