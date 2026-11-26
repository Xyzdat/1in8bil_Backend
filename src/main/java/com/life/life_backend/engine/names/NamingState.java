package com.life.life_backend.engine.names;

import com.life.life_backend.engine.local.LocalSimulation;
import java.util.*;
import java.util.stream.Collectors;

/** Server-only components of generated names. Never infer these components from user-edited strings. */
public record NamingState(String modelVersion, Map<String, PersonName> people) {
    public static final String VERSION = "country-names-1";
    public NamingState { people = Map.copyOf(people); }

    public NamingState with(String id, PersonName name) {
        var updated = new HashMap<>(people); updated.put(id, name);
        return new NamingState(modelVersion, updated);
    }

    public NamingState keepMatchingName(String id, String displayName) {
        var known = people.get(id);
        if (known == null || known.display().equals(displayName)) return this;
        var updated = new HashMap<>(people); updated.remove(id);
        return new NamingState(modelVersion, updated);
    }

    public NamingState reconcile(String heroName, String heroSex, List<LocalSimulation.Person> family) {
        var updated = new HashMap<String, PersonName>();
        var hero = people.get("character");
        if (matches(hero, heroName, heroSex)) updated.put("character", hero);
        for (var person : family) {
            var name = people.get(person.id());
            if (matches(name, person.name(), person.sex())) updated.put(person.id(), name);
        }
        return new NamingState(modelVersion, updated);
    }

    private static boolean matches(PersonName name, String display, String sex) {
        return name != null && name.display().equals(display) && name.sex().equals(sex);
    }

    public record PersonName(String countryIso3, String given, String sex, NameCatalog.Order order, List<NameCatalog.FamilyPart> family) {
        public PersonName { family = List.copyOf(family); }
        public String display() {
            String suffix = family.stream().map(part -> part.forSex(sex)).collect(Collectors.joining(" "));
            return order == NameCatalog.Order.FAMILY_FIRST ? suffix + " " + given : given + " " + suffix;
        }
    }
}
