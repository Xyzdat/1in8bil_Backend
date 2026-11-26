package com.life.life_backend.engine.names;

import com.life.life_backend.engine.local.LocalSimulation;
import java.util.*;
import static com.life.life_backend.engine.names.NameCatalog.*;
import static com.life.life_backend.engine.names.NamingState.*;

/** Separate random streams keep names from changing demographic draws, stats or future event choices. */
public final class PersonalNames {
    private PersonalNames() { }

    public static NamingState birth(NameCatalog catalog, String country, String given, String sex,
                                    List<LocalSimulation.Person> family, long seed) {
        var random = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
        var profile = catalog.get(country);
        var people = new LinkedHashMap<String, PersonName>();
        var father = family.stream().filter(person -> person.role().equals("father")).findFirst();
        var mother = family.stream().filter(person -> person.role().equals("mother")).findFirst();
        if (father.isPresent()) people.put(father.get().id(), unrelated(profile, father.get().name(), father.get().sex(), random));
        if (mother.isPresent()) {
            var value = unrelated(profile, mother.get().name(), mother.get().sex(), random);
            if (profile.rule() == Rule.SHARED && father.isPresent()) value = new PersonName(country, value.given(), value.sex(), value.order(), people.get(father.get().id()).family());
            people.put(mother.get().id(), value);
        }
        var parents = people.values().stream().toList();
        var hero = child(profile, given, sex, parents, random);
        people.put("character", hero);
        // Siblings share the same inherited components, including the fallback if a parent is absent.
        for (var person : family) if (!people.containsKey(person.id()))
            people.put(person.id(), new PersonName(country, person.name(), person.sex(), profile.order(), hero.family()));
        return new NamingState(VERSION, people);
    }

    public static PersonName newPerson(NameCatalog catalog, NamingState saved, String country, String given, String sex,
                                        List<String> parentIds, long seed, int month, String id) {
        if (!VERSION.equals(saved.modelVersion())) throw new IllegalStateException("Unsupported naming model");
        var random = new SplittableRandom(seed ^ (0x3C6EF372FE94F82BL * month) ^ id.hashCode());
        var parents = parentIds.stream().map(saved.people()::get).filter(Objects::nonNull).toList();
        return parentIds.isEmpty() ? unrelated(catalog.get(country), given, sex, random)
                : child(catalog.get(country), given, sex, parents, random);
    }

    private static PersonName unrelated(CountryNames profile, String given, String sex, SplittableRandom random) {
        var parts = new ArrayList<FamilyPart>(); parts.add(pick(profile, random));
        if (profile.rule() == Rule.DOUBLE_PATERNAL || profile.rule() == Rule.DOUBLE_MATERNAL) parts.add(pick(profile, random));
        return new PersonName(profile.iso3(), given, sex, profile.order(), parts);
    }

    private static PersonName child(CountryNames profile, String given, String sex, List<PersonName> parents, SplittableRandom random) {
        PersonName father = parents.stream().filter(parent -> parent.sex().equals("MALE")).findFirst().orElse(null);
        PersonName mother = parents.stream().filter(parent -> parent.sex().equals("FEMALE")).findFirst().orElse(null);
        PersonName primary = father != null ? father : parents.isEmpty() ? null : parents.getFirst();
        List<FamilyPart> parts;
        switch (profile.rule()) {
            case PATRONYMIC -> {
                // A missing father's name stays an authored fallback; no fictional parent is added to genealogy.
                parts = List.of(father == null ? pick(profile, random) : new FamilyPart(father.given(), father.given()));
            }
            case DOUBLE_PATERNAL, DOUBLE_MATERNAL -> {
                var paternal = inheritable(father, random, profile);
                var maternal = inheritable(mother, random, profile);
                parts = profile.rule() == Rule.DOUBLE_PATERNAL ? List.of(paternal, maternal) : List.of(maternal, paternal);
            }
            default -> parts = List.of(inheritable(primary, random, profile));
        }
        return new PersonName(profile.iso3(), given, sex, profile.order(), parts);
    }

    private static FamilyPart inheritable(PersonName parent, SplittableRandom random, CountryNames profile) {
        if (parent == null || parent.family().isEmpty()) return pick(profile, random);
        // In the authored Brazilian two-part convention the paternal family component is last.
        return parent.countryIso3().equals("BRA") ? parent.family().getLast() : parent.family().getFirst();
    }
    private static FamilyPart pick(CountryNames profile, SplittableRandom random) { return profile.families().get(random.nextInt(profile.families().size())); }
}
