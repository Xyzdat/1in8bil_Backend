package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.BirthEconomy;
import com.life.life_backend.statistics.SocioeconomicReference.Observation;
import java.util.*;

/** Income shares describe amounts, not the probabilities of selecting population quintiles. */
public final class FamilyResourcesModel {
    private static final List<String> SERIES = List.of("SI.DST.FRST.20", "SI.DST.02ND.20", "SI.DST.03RD.20", "SI.DST.04TH.20", "SI.DST.05TH.20");
    private static final List<String> LABELS = List.of("Низкий достаток", "Ниже среднего", "Средний достаток", "Выше среднего", "Высокий достаток");
    private FamilyResourcesModel() { }

    public static List<Observation> distribution(List<Observation> rows, int year) {
        var groups = new TreeMap<Integer, Map<String, Observation>>(Comparator.reverseOrder());
        for (var r : rows) if (r.year() <= year && SERIES.contains(r.indicator()))
            groups.computeIfAbsent(r.year(), ignored -> new HashMap<>()).put(r.indicator(), r);
        for (var group : groups.values()) {
            if (group.size() != 5) continue;
            var ordered = SERIES.stream().map(group::get).toList();
            double sum = ordered.stream().mapToDouble(Observation::value).sum();
            boolean valid = Math.abs(sum - 100) <= .26;
            for (int i = 0; i < ordered.size(); i++)
                valid &= Double.isFinite(ordered.get(i).value()) && ordered.get(i).value() >= 0
                        && (i == 0 || ordered.get(i).value() >= ordered.get(i - 1).value());
            if (valid) return ordered;
        }
        return List.of();
    }

    public static BirthEconomy.Resources sample(List<Observation> distribution, int quintile) {
        if (quintile < 1 || quintile > 5) throw new IllegalArgumentException("Quintile must be 1..5");
        if (distribution.isEmpty()) return new BirthEconomy.Resources(quintile, null, "GAME_ASSUMPTION", List.of(),
                "Нет полного сопоставимого распределения дохода/потребления: группа выбрана игровым правилом, индекс достатка неизвестен.");
        double total = distribution.stream().mapToDouble(Observation::value).sum();
        double relative = 5 * distribution.get(quintile - 1).value() / total;
        return new BirthEconomy.Resources(quintile, relative, "SAMPLED_PROXY", distribution.stream().map(Observation::evidence).toList(),
                "World Bank WDI, " + distribution.getFirst().year() + ": средний доход или потребление на человека в выбранной пятой группе относительно среднего страны. "
                        + "Каждая группа содержит 20% населения; доля дохода не является вероятностью выбора группы. "
                        + "Поправка на округление: 5 × доля / сумма пяти долей. Это приближение для семьи новорождённого без весов рождаемости, "
                        + "не накопленное имущество, не абсолютный доход семьи и не рубли. Связь с профессией родителей неизвестна.");
    }

    public static String label(BirthEconomy.Resources resources) {
        String label = LABELS.get(resources.quintile() - 1);
        return resources.relativeToCountryMean() == null ? label + " · игровая оценка"
                : label + " · " + String.format(Locale.forLanguageTag("ru"), "%.2f", resources.relativeToCountryMean()) + " среднего по стране";
    }
}
