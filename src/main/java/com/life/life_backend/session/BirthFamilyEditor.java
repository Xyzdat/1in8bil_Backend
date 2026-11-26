package com.life.life_backend.session;

import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Birth-only replacement: there are no relationships, story participants or NPC careers to orphan yet. */
final class BirthFamilyEditor {
    private static final List<String> WEALTH = List.of("Низкий достаток", "Ниже среднего", "Средний достаток", "Выше среднего", "Высокий достаток");

    List<LocalSimulation.Person> members(List<LocalSimulation.Person> existing, List<CharacterChanges.Member> requested) {
        if (requested == null) return existing;
        var old = new HashMap<String, LocalSimulation.Person>();
        existing.forEach(p -> old.put(p.id(), p));
        var used = new HashSet<String>();
        var people = new ArrayList<LocalSimulation.Person>();
        for (var member : requested) {
            if (member.id() != null && !old.containsKey(member.id())) throw invalid("Unknown family member id.");
            String id = member.id() == null ? "relative-" + UUID.randomUUID() : member.id();
            if (!used.add(id)) throw invalid("Duplicate family member id.");
            if (isParent(member.role()) && member.ageAtBirth() < 18) throw invalid("A parent must be at least 18 at the character's birth.");
            var previous = old.get(id);
            String job = member.occupation() == null ? (previous == null ? null : previous.occupation()) : emptyToNull(member.occupation());
            int closeness = member.closeness() == null ? (previous == null ? 65 : previous.closeness()) : member.closeness();
            int trust = member.trust() == null ? (previous == null ? 60 : previous.trust()) : member.trust();
            people.add(new LocalSimulation.Person(id, member.role(), member.name(), -member.ageAtBirth() * 12, true, closeness, trust,
                    Set.of("father", "brother").contains(member.role()) ? "MALE" : "FEMALE", job));
        }
        var parents = people.stream().filter(p -> isParent(p.role())).toList();
        if (parents.size() > 2) throw invalid("At most two parents can be configured.");
        int oldestSibling = people.stream().filter(p -> !isParent(p.role())).mapToInt(p -> -p.birthMonth() / 12).max().orElse(0);
        if (people.size() - parents.size() > 10) throw invalid("At most ten siblings can be configured.");
        if (parents.stream().anyMatch(p -> -p.birthMonth() / 12 - oldestSibling < 16))
            throw invalid("A configured parent must be at least 16 years older than the oldest sibling.");
        return List.copyOf(people);
    }

    CharacterProfile.Background background(CharacterProfile.Background old, CharacterChanges.Family edits, List<LocalSimulation.Person> people) {
        if (old == null) old = new CharacterProfile.Background(familyLabel(people),
                String.valueOf(people.stream().filter(p -> !isParent(p.role())).count()), null, null, null,
                parentLabel(people,"father"), parentLabel(people,"mother"), null, null, null);
        if (edits == null) return old;
        boolean replaced = edits.members() != null;
        return new CharacterProfile.Background(replaced ? familyLabel(people) : old.family(),
                replaced ? String.valueOf(people.stream().filter(p -> !isParent(p.role())).count()) : old.siblings(), old.pets(),
                choose(edits.housing(), old.housing()), choose(edits.relationships(), replaced ? relationshipLabel(people) : old.relationships()),
                replaced ? parentLabel(people, "father") : old.father(), replaced ? parentLabel(people, "mother") : old.mother(),
                edits.incomeQuintile() == null ? old.wealth() : WEALTH.get(edits.incomeQuintile() - 1),
                choose(edits.religion(), old.religion()), choose(edits.language(), old.language()));
    }

    BirthDetails details(BirthDetails old, CharacterChanges changes, List<LocalSimulation.Person> people) {
        var family = changes.family();
        boolean replaced = old == null || family != null && family.members() != null;
        if (old == null) old = new BirthDetails(null,null,null,null,null,null,null,List.of(),null,null,null,null,null,null,List.of());
        var mothers = people.stream().filter(p -> p.role().equals("mother")).toList();
        var fathers = people.stream().filter(p -> p.role().equals("father")).toList();
        var siblings = people.stream().filter(p -> !isParent(p.role())).toList();
        BirthDetails.FamilyType type = mothers.size() == 2 ? BirthDetails.FamilyType.TWO_MOTHERS
                : fathers.size() == 2 ? BirthDetails.FamilyType.TWO_FATHERS
                : mothers.size() == 1 && fathers.size() == 1 ? BirthDetails.FamilyType.MOTHER_AND_FATHER : null;
        return new BirthDetails(changes.birthplace() != null && changes.birthplace().placeType() != null ? changes.birthplace().placeType() : old.placeType(),
                replaced ? type : old.familyType(), replaced ? (int) siblings.stream().filter(p -> p.birthMonth() < 0).count() : old.olderSiblingCount(),
                family != null && family.incomeQuintile() != null ? family.incomeQuintile() : old.familyIncomeQuintile(),
                replaced ? !fathers.isEmpty() : old.fatherPresent(), replaced ? parent(mothers) : old.mother(), replaced ? parent(fathers) : old.father(),
                replaced ? siblings.stream().map(p -> new BirthDetails.Sibling(-p.birthMonth() / 12, BirthDetails.Sex.valueOf(p.sex()), p.birthMonth() < 0, true)).toList() : old.siblings(),
                replaced ? new BirthDetails.Household(people.size() + 1, !mothers.isEmpty(), !fathers.isEmpty(), false, false, false, siblings.size(), 0, 0) : old.household(),
                old.homeOwned(), old.electricityAtHome(), family != null && family.religion() != null ? null : old.faithCode(), old.group(), old.caste(), old.disabilityCodes());
    }

    String familyLabel(List<LocalSimulation.Person> people) {
        long mothers = people.stream().filter(p -> p.role().equals("mother")).count();
        long fathers = people.stream().filter(p -> p.role().equals("father")).count();
        if (mothers == 2) return "Две мамы";
        if (fathers == 2) return "Два папы";
        if (mothers == 1 && fathers == 1) return "Мать и отец";
        if (mothers == 1) return "Мать";
        if (fathers == 1) return "Отец";
        return "Родители не указаны";
    }

    private String parentLabel(List<LocalSimulation.Person> people, String role) {
        String value = people.stream().filter(p -> p.role().equals(role)).map(p -> p.name() + (p.occupation() == null ? "" : ", " + p.occupation()))
                .collect(java.util.stream.Collectors.joining("; "));
        return value.isEmpty() ? null : value.substring(0, Math.min(value.length(), 120));
    }
    private String relationshipLabel(List<LocalSimulation.Person> people) {
        if (people.isEmpty()) return "Родственники не указаны";
        double average = people.stream().mapToInt(LocalSimulation.Person::closeness).average().orElse(0);
        return average >= 65 ? "Тёплые отношения" : average >= 40 ? "Сдержанные отношения" : "Напряжённые отношения";
    }
    private BirthDetails.Parent parent(List<LocalSimulation.Person> people) {
        if (people.isEmpty()) return null;
        var p = people.getFirst();
        return new BirthDetails.Parent(-p.birthMonth() / 12, p.occupation() == null ? null : new BirthDetails.Occupation("custom", p.occupation()), true, true);
    }
    private boolean isParent(String role) { return role.equals("mother") || role.equals("father"); }
    private String choose(String value, String fallback) { return value == null ? fallback : value; }
    private String emptyToNull(String value) { return value.isEmpty() ? null : value; }
    private ApiFailure invalid(String text) { return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_FAMILY", text); }
}
