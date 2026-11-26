package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.life.life_backend.statistics.SocioeconomicCalibration.*;
import static com.life.life_backend.statistics.SocioeconomicReference.Observation;

/** Population aggregates calibrate explicit proxies, not an observed joint newborn distribution. */
@Component
public class FamilyHousingModel {
    private static final String PREFIX = "UN.HH.";
    private final SocioeconomicReference reference;
    public FamilyHousingModel(SocioeconomicReference reference) { this.reference = reference; }

    public Family sample(String country, int birthYear, int motherAge, int fatherAge, long seed) {
        var rows = reference.observations(SocioeconomicBundle.FAMILY, country).stream().filter(r -> r.year() <= birthYear).toList();
        var random = new SplittableRandom(seed ^ 0x4F3759DADL);
        var evidence = new ArrayList<Evidence>();
        var notes = new ArrayList<String>();
        notes.add("Household categories conditioned on families with children are a newborn-family proxy, not birth-weighted probabilities.");
        notes.add("Parent ages, sex of siblings, age gaps and closeness remain game assumptions. Household residents are not all siblings.");
        var parents = cohort(rows, List.of("TYPE_COUPLE_CHILDREN", "TYPE_SINGLE_MOTHER", "TYPE_SINGLE_FATHER"));
        boolean mother = true, father = true;
        if (!parents.isEmpty()) {
            parents.forEach(r -> evidence.add(r.evidence()));
            String chosen = draw(parents, random).indicator();
            mother = !chosen.equals(PREFIX + "TYPE_SINGLE_FATHER");
            father = !chosen.equals(PREFIX + "TYPE_SINGLE_MOTHER");
        } else notes.add("No complete parent-type cohort: legacy two-parent assumption used.");

        // This mean is conditional on households with children, not a count of siblings.
        var childMean = rows.stream().filter(r -> r.indicator().equals(PREFIX + "MEMBERS_UNDER15_CONDITIONAL_MEAN")).findFirst();
        int parentAge = mother ? motherAge : fatherAge;
        int capacity = Math.min(5, Math.max(0, (parentAge - 18) / 2));
        int siblings;
        if (childMean.isPresent()) {
            var mean = childMean.get(); evidence.add(mean.evidence());
            double expected = Math.clamp(mean.value() - 1, 0, capacity);
            siblings = (int) expected + (random.nextDouble() < expected % 1 ? 1 : 0);
            notes.add("Older siblings approximate conditional under-15 mean minus the newborn, with stochastic rounding and age cap; this distribution is a game assumption.");
        } else {
            siblings = random.nextInt(Math.min(3, capacity) + 1);
            notes.add("Conditional child count missing: bounded legacy sibling-count assumption used.");
        }
        int minimum = 1 + (mother ? 1 : 0) + (father ? 1 : 0) + siblings;
        int householdSize = minimum;
        var sizes = cohort(rows, List.of("SIZE_1", "SIZE_2_3", "SIZE_4_5", "SIZE_6_PLUS"));
        if (!sizes.isEmpty()) {
            sizes.forEach(r -> evidence.add(r.evidence()));
            var eligible = sizes.stream().filter(r -> upper(r.indicator()) >= minimum && r.value() > 0).toList();
            if (!eligible.isEmpty()) {
                String size = draw(eligible, random).indicator();
                householdSize = random.nextInt(Math.max(minimum, lower(size)), Math.max(minimum, upper(size)) + 1);
            }
            notes.add("Household size sampled separately, conditioned to fit known family; uniform within buckets, 6+ capped at 8. Other residents are not generated as siblings.");
        } else notes.add("No complete household-size cohort: size equals the known family.");
        Boolean electricity = utility(rows, "EG.ELC.ACCS.ZS", random, evidence);
        Boolean water = utility(rows, "SH.H2O.BASW.ZS", random, evidence);
        Boolean sanitation = utility(rows, "SH.STA.BASS.ZS", random, evidence);
        notes.add("Utilities independently sampled from population percentages, not joint household data. Housing form and ownership are not inferred from these percentages.");
        return new Family(country, mother, father, siblings, householdSize, electricity, water, sanitation, evidence, notes);
    }

    private List<Observation> cohort(List<Observation> rows, List<String> names) {
        var groups = new LinkedHashMap<String, Map<String, Observation>>();
        for (var row : rows) if (names.stream().anyMatch(n -> row.indicator().equals(PREFIX + n))) {
            String key = row.year() + ":" + row.dimension("sourceCategory") + ":" + row.dimension("sourceCatalogId");
            groups.computeIfAbsent(key, ignored -> new TreeMap<>()).put(row.indicator(), row);
        }
        return groups.values().stream().filter(g -> g.size() == names.size() && g.values().stream().mapToDouble(Observation::value).sum() > 0)
                .map(g -> names.stream().map(n -> g.get(PREFIX + n)).toList()).findFirst().orElse(List.of());
    }
    private Observation draw(List<Observation> rows, SplittableRandom random) {
        double value = random.nextDouble(rows.stream().mapToDouble(Observation::value).sum());
        for (var row : rows) { value -= row.value(); if (value < 0) return row; }
        return rows.getLast();
    }
    private Boolean utility(List<Observation> rows, String indicator, SplittableRandom random, List<Evidence> evidence) {
        var value = rows.stream().filter(r -> r.indicator().equals(indicator)).findFirst();
        if (value.isEmpty()) return null;
        evidence.add(value.get().evidence());
        return random.nextDouble() < value.get().value() / 100;
    }
    private int lower(String indicator) { return indicator.endsWith("SIZE_1") ? 1 : indicator.endsWith("SIZE_2_3") ? 2 : indicator.endsWith("SIZE_4_5") ? 4 : 6; }
    private int upper(String indicator) { return indicator.endsWith("SIZE_1") ? 1 : indicator.endsWith("SIZE_2_3") ? 3 : indicator.endsWith("SIZE_4_5") ? 5 : 8; }

    public String housing(String form, Family family) {
        return form + " · " + utilityLabel(family.electricity(), "электричество", "без электричества")
                + "; " + utilityLabel(family.water(), "базовое водоснабжение", "без базового водоснабжения")
                + "; " + utilityLabel(family.sanitation(), "базовая санитария", "без базовой санитарии");
    }
    private String utilityLabel(Boolean value, String yes, String no) { return value == null ? yes + ": нет данных" : value ? yes : no; }
}
