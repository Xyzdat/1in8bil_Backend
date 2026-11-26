package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.BirthEconomy;
import com.life.life_backend.statistics.SocioeconomicCalibration.Evidence;
import com.life.life_backend.statistics.SocioeconomicReference.Observation;
import java.util.*;

/** Samples occupational groups among employed people, without inventing specialties or missing cells. */
public final class ParentOccupationModel {
    private static final String SERIES = "EMP_TEMP_SEX_OCU_NB";
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("0", "Военнослужащий"), Map.entry("1", "Руководитель"), Map.entry("2", "Специалист высшей квалификации"),
            Map.entry("3", "Специалист среднего уровня квалификации"), Map.entry("4", "Офисный служащий"),
            Map.entry("5", "Работник торговли и услуг"), Map.entry("6", "Работник сельского хозяйства"),
            Map.entry("7", "Квалифицированный рабочий"), Map.entry("8", "Оператор оборудования"),
            Map.entry("9", "Работник простых профессий"), Map.entry("96", "Сельское хозяйство и простые профессии"), Map.entry("X", "Сфера работы не указана"));
    private ParentOccupationModel() { }
    public record Distribution(String sex, Map<String, Double> weights, double classifiedShare, List<Evidence> evidence, String note) {
        public Distribution { weights = Collections.unmodifiableMap(new TreeMap<>(weights)); evidence = List.copyOf(evidence); }
    }

    public static Distribution distribution(List<Observation> rows, int year, String requestedSex) {
        return distribution(rows, year, requestedSex, SERIES);
    }
    public static Distribution distribution(List<Observation> rows, int year, String requestedSex, String indicator) {
        boolean modelled = indicator.equals("EMP_2EMP_SEX_OCU_NB");
        for (String sex : List.of(requestedSex, "SEX_T")) {
            var groups = new TreeMap<String, Map<String, Observation>>();
            for (var r : rows) {
                String classification = r.dimension("classif1");
                if (r.year() > year || !r.indicator().equals(indicator) || !r.dimension("sex").equals(sex)
                        || !classification.matches(modelled ? "OCU_ISCO08_(TOTAL|[0-9]|96|X)" : "OCU_ISCO(08|88)_(TOTAL|[0-9]|X)")) continue;
                String prefix = classification.substring(0, 11);
                String key = String.format(Locale.ROOT, "%04d", 9999 - r.year()) + ":" + prefix + ":" + r.dimension("source")
                        + ":" + r.provenance().path("note_source").asString("") + ":" + r.provenance().path("note_indicator").asString("")
                        + ":" + r.provenance().path("obs_status").asString("");
                groups.computeIfAbsent(key, ignored -> new TreeMap<>()).put(classification, r);
            }
            for (var group : groups.values()) {
                var total = group.values().stream().filter(r -> r.dimension("classif1").endsWith("TOTAL")
                        && r.value() > 0 && standard(r)).findFirst();
                if (total.isEmpty()) continue;
                var all = total.get(); String prefix = all.dimension("classif1").replace("TOTAL", "");
                if (group.containsKey(prefix + "96") && (group.containsKey(prefix + "6") || group.containsKey(prefix + "9"))) continue;
                var known = group.values().stream().filter(r -> r.dimension("classif1").matches(prefix + (modelled ? "([0-9]|96)" : "[0-9]")) && standard(r)).toList();
                double sum = known.stream().mapToDouble(Observation::value).sum();
                var unspecified = group.get(prefix + "X");
                double reportedUnspecified = unspecified != null && standard(unspecified) ? unspecified.value() : 0;
                // More than a tiny rounding discrepancy is an inconsistent cohort, not something to normalise away.
                if (sum <= 0 || sum + reportedUnspecified > all.value() + Math.max(.01, all.value() * .0001)) continue;
                var weights = new TreeMap<String, Double>(); var evidence = new ArrayList<Evidence>(); evidence.add(all.evidence());
                if (unspecified != null && standard(unspecified)) evidence.add(unspecified.evidence());
                double denominator = Math.max(sum, all.value());
                for (var r : known) {
                    evidence.add(r.evidence());
                    if (r.value() > 0) weights.put(r.dimension("classif1"), r.value() / denominator);
                }
                double residual = Math.max(0, 1 - sum / denominator);
                if (residual > 0) weights.put(prefix + "X", residual);
                return new Distribution(sex, weights, sum / denominator, evidence,
                        "ILOSTAT, " + all.year() + ", " + all.dimension("source") + ", " + prefix + ", " + sex + ". "
                                + (modelled ? "Модельная оценка ILOEST Nov. 2025, не прямое наблюдение. Группа 96 объединяет 6 и 9; статистического разделения нет. " : "")
                                + "Доли занятых, не вероятность трудоустройства или распределение профессий родителей новорождённых. "
                                + "Выбрана профессиональная группа; узкая специальность неизвестна. Нестандартные/неизвестные группы и пропуски остаются неуказанной сферой. "
                                + "Пол, год, обследование, классификатор и общие примечания согласованы. Родители и достаток выбираются независимо: совместных данных нет."
                                + (sex.equals(requestedSex) ? "" : " Использовано распределение обоих полов из-за отсутствия сопоставимого ряда для пола родителя."));
            }
        }
        return new Distribution(requestedSex, Map.of(), 0, List.of(), "Нет сопоставимого распределения профессий страны: занятие родителя не выдумывается.");
    }

    private static boolean standard(Observation row) {
        // A note may merge occupations (e.g. group 2 includes 3). Do not silently treat it as a pure major group.
        return row.provenance().path("note_classif").asString("").isEmpty();
    }
    public static BirthEconomy.Occupation sample(Distribution distribution, SplittableRandom random) {
        if (distribution.weights().isEmpty()) return new BirthEconomy.Occupation("UNSPECIFIED", "Нет данных о профессии", "MISSING",
                distribution.sex(), 1, 0, List.of(), distribution.note());
        double draw = random.nextDouble(); String code = distribution.weights().keySet().stream().reduce((a, b) -> b).orElseThrow();
        for (var entry : distribution.weights().entrySet()) { draw -= entry.getValue(); if (draw < 0) { code = entry.getKey(); break; } }
        String major = code.substring(code.lastIndexOf('_') + 1);
        return new BirthEconomy.Occupation(code, LABELS.get(major), "SAMPLED_PROXY", distribution.sex(), distribution.weights().get(code),
                distribution.classifiedShare(), distribution.evidence(), distribution.note());
    }
}
