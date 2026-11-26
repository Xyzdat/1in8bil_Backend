package com.life.life_backend.statistics;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.life.life_backend.statistics.SocioeconomicReference.Observation;
import static com.life.life_backend.statistics.SocioeconomicCalibration.*;

/** Ratios only: local-currency wages are never mislabeled as RUB. Each ratio uses one survey/year. */
@Component
public class LabourCalibrationFactory {
    private static final String OCCUPATION = "EAR_EMTA_SEX_OCU_CUR_NB";
    private static final String MEAN = "EAR_EMTA_SEX_CUR_NB", MEDIAN = "EAR_EMTM_SEX_CUR_NB";
    private final SocioeconomicReference reference;
    private final CountryCatalog countries;
    private final Map<Integer, Map<String, Labour>> cache = new ConcurrentHashMap<>();
    public LabourCalibrationFactory(SocioeconomicReference reference, CountryCatalog countries) {
        this.reference = reference; this.countries = countries;
    }

    public Map<String, Labour> snapshot(int birthYear) {
        return cache.computeIfAbsent(birthYear, year -> {
            var result = new TreeMap<String, Labour>();
            for (String country : countries.codes()) result.put(countries.get(country).iso2(), calibrate(
                    reference.observations(SocioeconomicBundle.WORK, country).stream().filter(r -> r.year() <= year).toList()));
            return Map.copyOf(result);
        });
    }

    public static Labour calibrate(List<Observation> input) {
        // Do not vary pay by the player's sex; aggregates for all sexes only.
        var rows = input.stream().filter(r -> r.dimension("sex").equals("SEX_T") && r.value() > 0)
                .sorted(Comparator.comparingInt(Observation::year).reversed().thenComparing(Observation::key)).toList();
        var evidence = new ArrayList<Evidence>(); var notes = new ArrayList<String>();
        var ratios = new TreeMap<String, Double>();
        for (String classification : List.of("OCU_ISCO08_", "OCU_ISCO88_")) {
            var cohorts = new LinkedHashMap<String, Map<String, Observation>>();
            for (var r : rows) if (r.indicator().equals(OCCUPATION) && r.dimension("classif2").equals("CUR_TYPE_LCU")
                    && (r.dimension("classif1").equals(classification + "TOTAL") || r.dimension("classif1").matches(classification + "[1-9]"))) {
                cohorts.computeIfAbsent(cohort(r), ignored -> new TreeMap<>()).put(r.dimension("classif1"), r);
            }
            var selected = cohorts.values().stream().filter(g -> g.containsKey(classification + "TOTAL") && g.size() >= 2).findFirst();
            if (selected.isEmpty()) continue;
            var group = selected.get(); var total = group.get(classification + "TOTAL");
            evidence.add(total.evidence());
            for (var r : group.values()) if (!r.indicator().isBlank() && !r.dimension("classif1").endsWith("TOTAL")) {
                String major = r.dimension("classif1").substring(classification.length());
                ratios.put(major, Math.clamp(r.value() / total.value(), .4, 3)); evidence.add(r.evidence());
            }
            notes.add("Occupation/total mean from " + classification + ", source " + total.dimension("source") + ", " + total.year()
                    + "; ratios bounded to 0.4–3 for gameplay. Job-to-major-group mapping is an assumption.");
            break;
        }
        // Fallback for unmapped occupations: median/mean in exactly the same survey and year.
        for (var median : rows) if (median.indicator().equals(MEDIAN) && median.dimension("classif1").equals("CUR_TYPE_LCU")) {
            var mean = rows.stream().filter(r -> r.indicator().equals(MEAN) && r.dimension("classif1").equals("CUR_TYPE_LCU")
                    && cohort(r).equals(cohort(median))).findFirst();
            if (mean.isPresent()) {
                ratios.put("TYPICAL", Math.clamp(median.value() / mean.get().value(), .4, 1.5));
                evidence.add(mean.get().evidence()); evidence.add(median.evidence()); break;
            }
        }
        if (!ratios.containsKey("TYPICAL")) notes.add("No compatible median/mean cohort: uncalibrated occupations retain the legacy scale (factor 1).");
        if (ratios.keySet().stream().noneMatch(k -> k.matches("[1-9]"))) notes.add("No compatible occupation/total cohort: no occupation wage differentials inferred.");
        notes.add("Frozen at birth. All-sex ratios calibrate a RUB game scale, not an FX/PPP conversion or a national wage forecast. Hiring, business income, pension and expenses remain game rules.");
        return new Labour(ratios, evidence, notes);
    }

    private static String cohort(Observation row) {
        return row.year() + ":" + row.dimension("source") + ":" + row.provenance().path("note_indicator").asString("")
                + ":" + row.provenance().path("obs_status").asString("");
    }
}
