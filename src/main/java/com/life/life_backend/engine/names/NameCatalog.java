package com.life.life_backend.engine.names;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Authored Russian-language name examples, not population frequencies or a legal naming model. */
public final class NameCatalog {
    private final Map<String, CountryNames> countries;

    public NameCatalog(ObjectMapper json, Set<String> supportedCountries) throws Exception {
        var loaded = new TreeMap<String, CountryNames>();
        try (var in = new ClassPathResource("names/country-names-1.json").getInputStream()) {
            for (var country : json.readValue(in, CountryNames[].class)) {
                if (country.order() == null || country.rule() == null || country.families().isEmpty()
                        || country.families().stream().anyMatch(part -> part.male().isBlank() || part.female().isBlank())
                        || loaded.put(country.iso3(), country) != null) throw new IllegalStateException("Invalid name catalog");
            }
        }
        if (!loaded.keySet().equals(supportedCountries)) throw new IllegalStateException("Name catalog must cover all supported countries");
        countries = Collections.unmodifiableMap(loaded);
    }

    public CountryNames get(String country) { return Objects.requireNonNull(countries.get(country), "Unsupported name country: " + country); }
    public enum Order { GIVEN_FIRST, FAMILY_FIRST }
    public enum Rule { SHARED, SEPARATE, DOUBLE_PATERNAL, DOUBLE_MATERNAL, PATRONYMIC }
    public record FamilyPart(String male, String female) {
        public String forSex(String sex) { return "FEMALE".equals(sex) ? female : male; }
    }
    public record CountryNames(String iso3, Order order, Rule rule, List<FamilyPart> families) {
        public CountryNames { families = List.copyOf(families); }
    }
}
