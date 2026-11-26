package com.life.life_backend.statistics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Supported country codes and authored display/name lists; names are not demographic statistics. */
@Component
public class CountryCatalog {
    private final Map<String, Country> countries;
    private final com.life.life_backend.engine.names.NameCatalog names;

    public CountryCatalog(ObjectMapper json) throws Exception {
        var loaded = new TreeMap<String, Country>();
        try (var stream = new ClassPathResource("statistics/country-catalog.json").getInputStream()) {
            for (var country : json.readValue(stream, Country[].class)) {
                if (!country.iso3().matches("[A-Z]{3}") || !country.iso2().matches("[A-Z]{2}")
                        || country.name().isBlank() || country.maleNames().isEmpty() || country.femaleNames().isEmpty()
                        || loaded.put(country.iso3(), country) != null) throw new IllegalStateException("Invalid country catalog");
            }
        }
        countries = Collections.unmodifiableMap(loaded);
        names = new com.life.life_backend.engine.names.NameCatalog(json, countries.keySet());
    }
    public com.life.life_backend.engine.names.NameCatalog names() { return names; }
    public Set<String> codes() { return countries.keySet(); }
    public Country get(String iso) { return Objects.requireNonNull(countries.get(iso), "Unsupported country: " + iso); }
    public String iso3(String iso2) {
        return countries.values().stream().filter(c -> c.iso2().equals(iso2)).map(Country::iso3).findFirst().orElseThrow();
    }
    public record Country(String iso3, String iso2, String name, String pew, List<String> maleNames, List<String> femaleNames) { }
}
