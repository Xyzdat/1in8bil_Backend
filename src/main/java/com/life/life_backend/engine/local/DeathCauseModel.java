package com.life.life_backend.engine.local;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Conditional attribution AFTER the all-cause death draw. Weights are authored game rules, not statistics. */
public final class DeathCauseModel {
    public static final String VERSION = "death-causes-1";

    public enum Code {
        PERINATAL_COMPLICATIONS("Осложнения перинатального периода"),
        CONGENITAL_CONDITION("Осложнения врождённого заболевания"),
        RESPIRATORY_INFECTION("Тяжёлая пневмония"),
        SEVERE_INFECTION("Тяжёлая инфекция"),
        ACCIDENT("Смертельная травма при несчастном случае"),
        HEART_ATTACK("Инфаркт миокарда"),
        STROKE("Инсульт"),
        CANCER("Онкологическое заболевание"),
        ACUTE_DISEASE_COMPLICATIONS("Осложнения текущего заболевания"),
        CHRONIC_DISEASE_COMPLICATIONS("Осложнения хронического заболевания"),
        INJURY_COMPLICATIONS("Осложнения недавней травмы");

        private final String label;
        Code(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Details(Code code, String label, String model, String basis, String conditionId) { }

    /** The local engine does not depend on the adult module's internal disease record. */
    public record Condition(String id, String kind, int onsetMonth, int severity,
                            boolean active, boolean chronic, boolean supervised) { }

    public record Context(List<Condition> conditions, int occupationalRisk) {
        public static final Context EMPTY = new Context(List.of(), 0);
        public Context { conditions = List.copyOf(conditions); }
    }

    record Candidate(Code code, double weight, String conditionId) { }

    /** Critical health is a terminal gameplay condition, not another random mortality draw. */
    public Details depletedHealth(int month, Context context) {
        var condition = context.conditions().stream().filter(c -> c.active() && c.onsetMonth() <= month && c.severity() > 0)
                .sorted(java.util.Comparator.comparingInt(Condition::severity).reversed().thenComparing(Condition::id)).findFirst();
        if (condition.isPresent()) {
            var c = condition.get();
            var code = c.kind().equals("INJURY") ? Code.INJURY_COMPLICATIONS
                    : c.chronic() ? Code.CHRONIC_DISEASE_COMPLICATIONS : Code.ACUTE_DISEASE_COMPLICATIONS;
            return new Details(code, code.label(), VERSION, "GAME_ASSUMPTION", c.id());
        }
        return new Details(Code.ACUTE_DISEASE_COMPLICATIONS, "Критическое ухудшение здоровья", VERSION, "GAME_ASSUMPTION", null);
    }

    public Details determine(long seed, String personId, int deathMonth, int ageMonths, int health, Context context) {
        var candidates = candidates(deathMonth, ageMonths, health, context);
        // An independent stable stream: attributing a cause cannot change death time or future events.
        long personSeed = 0xcbf29ce484222325L;
        for (int i = 0; i < personId.length(); i++) personSeed = (personSeed ^ personId.charAt(i)) * 0x100000001b3L;
        var random = new SplittableRandom(seed ^ personSeed ^ 0xa0761d6478bd642fL ^ (0x9e3779b97f4a7c15L * deathMonth));
        double draw = random.nextDouble(candidates.stream().mapToDouble(Candidate::weight).sum());
        var selected = candidates.getLast();
        for (var candidate : candidates) {
            draw -= candidate.weight();
            if (draw < 0) { selected = candidate; break; }
        }
        return new Details(selected.code(), selected.code().label(), VERSION, "GAME_ASSUMPTION", selected.conditionId());
    }

    List<Candidate> candidates(int deathMonth, int ageMonths, int health, Context context) {
        var choices = new ArrayList<Candidate>();
        int age = Math.max(0, ageMonths) / 12;
        double burden = 1 + (100 - Math.clamp(health, 0, 100)) / 100d;
        // No country-specific cause distribution has been imported. These weights apply to all countries.
        if (ageMonths <= 1) add(choices, Code.PERINATAL_COMPLICATIONS, 60 * burden);
        if (ageMonths < 12) add(choices, Code.CONGENITAL_CONDITION, 35 * burden);
        if (ageMonths >= 1) add(choices, Code.RESPIRATORY_INFECTION, (age < 5 || age >= 65 ? 30 : 12) * burden);
        add(choices, Code.SEVERE_INFECTION, (age < 5 ? 30 : 10) * burden);
        if (ageMonths >= 12) {
            double occupational = age >= 18 ? Math.clamp(context.occupationalRisk(), 0, 100) / 100d : 0;
            add(choices, Code.ACCIDENT, (age < 35 ? 40 : 12) * (1 + occupational));
            add(choices, Code.CANCER, (age < 18 ? 4 : age < 45 ? 15 : 35) * burden);
        }
        if (age >= 35) add(choices, Code.HEART_ATTACK, (age < 65 ? 25 : 40) * burden);
        if (age >= 45) add(choices, Code.STROKE, (age < 65 ? 15 : 35) * burden);

        // A generic health episode is never silently reclassified as a diagnosed cancer or heart disease.
        for (var condition : context.conditions().stream().sorted(java.util.Comparator.comparing(Condition::id)).toList()) {
            if (!condition.active() || condition.onsetMonth() > deathMonth || condition.severity() <= 0) continue;
            Code code;
            if (condition.kind().equals("INJURY")) {
                // Old healed injuries and mild residual limitations cannot explain a new fatal injury.
                if (condition.chronic() || condition.severity() < 20 || deathMonth - condition.onsetMonth() > 12) continue;
                code = Code.INJURY_COMPLICATIONS;
            } else if (condition.kind().equals("HEALTH_CONDITION")) {
                code = condition.chronic() ? Code.CHRONIC_DISEASE_COMPLICATIONS : Code.ACUTE_DISEASE_COMPLICATIONS;
            } else continue;
            double weight = Math.clamp(condition.severity(), 1, 100) * burden * (condition.supervised() ? .5 : 1);
            choices.add(new Candidate(code, weight, condition.id()));
        }
        return List.copyOf(choices);
    }

    private static void add(List<Candidate> choices, Code code, double weight) {
        choices.add(new Candidate(code, weight, null));
    }
}
