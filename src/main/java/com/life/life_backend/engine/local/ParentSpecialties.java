package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.BirthEconomy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Authored detail within a sampled group; never presented as a measured specialty frequency. */
@Component
public class ParentSpecialties {
    public static final String VERSION = "parent-specialties-1";
    private final Map<String, List<Specialty>> groups;
    public ParentSpecialties(ObjectMapper json) throws Exception {
        try (var in = new ClassPathResource("occupations/parent-specialties-1.json").getInputStream()) {
            var file = json.readValue(in, Catalog.class);
            if (!VERSION.equals(file.modelVersion()) || !"GAME_ASSUMPTION".equals(file.basis()) || file.groups().size() != 10)
                throw new IllegalStateException("Invalid specialty catalog");
            var loaded = new HashMap<String, List<Specialty>>(); var codes = new HashSet<String>();
            for (int i = 0; i <= 9; i++) {
                var rows = file.groups().get(Integer.toString(i));
                if (rows == null || rows.isEmpty()) throw new IllegalStateException("Missing occupation group");
                for (var row : rows) if (!codes.add(row.code()) || row.label().isBlank()) throw new IllegalStateException("Invalid specialty");
                loaded.put(Integer.toString(i), List.copyOf(rows));
            }
            var combined = new ArrayList<>(loaded.get("6")); combined.addAll(loaded.get("9"));
            loaded.put("96", List.copyOf(combined)); groups = Map.copyOf(loaded);
        }
    }

    public BirthEconomy.Occupation specify(BirthEconomy.Occupation group, long seed) {
        if (group == null) return null;
        String major = group.code().substring(group.code().lastIndexOf('_') + 1);
        var candidates = groups.get(major);
        if (candidates == null) return group; // Missing/unknown groups must remain unknown.
        var chosen = candidates.get(new SplittableRandom(seed).nextInt(candidates.size()));
        var detail = new BirthEconomy.Specialization(VERSION, chosen.code(), chosen.label(), "GAME_ASSUMPTION", 1d / candidates.size());
        return new BirthEconomy.Occupation(group.code(), chosen.label() + " · игровая специализация", group.basis(), group.sampledSex(),
                group.probability(), group.classifiedShare(), group.evidence(), group.note()
                + " Исходная группа: «" + group.label() + "». Конкретная профессия «" + chosen.label()
                + "» выбрана равномерно из игрового справочника " + VERSION + "; это не статистика частоты специальностей страны. "
                + "Образование, доход и квалификация родителя из названия не выводятся.", detail);
    }

    private record Specialty(String code, String label) { }
    private record Catalog(String modelVersion, String basis, String note, Map<String, List<Specialty>> groups) { }
}
