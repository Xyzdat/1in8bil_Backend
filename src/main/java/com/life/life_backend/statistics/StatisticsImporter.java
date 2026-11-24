package com.life.life_backend.statistics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

@Service
public class StatisticsImporter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ExpandedStatisticsImporter expanded;
    private final CountryExpansionImporter countries;
    public StatisticsImporter(JdbcTemplate jdbc, ObjectMapper json, ExpandedStatisticsImporter expanded, CountryExpansionImporter countries) {
        this.jdbc = jdbc; this.json = json; this.expanded = expanded; this.countries = countries;
    }

    @Transactional
    public String importBundled() throws Exception {
        byte[] bytes;
        try (var stream = new ClassPathResource("statistics/observations.json").getInputStream()) { bytes = stream.readAllBytes(); }
        Manifest manifest;
        try (var stream = new ClassPathResource("statistics/manifest.json").getInputStream()) { manifest = json.readValue(stream, Manifest.class); }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals(manifest.sha256())) throw new IllegalStateException("Statistics checksum mismatch");
        var root = json.readTree(bytes);
        if (root.get(0).path("pages").asInt() != 1 || root.get(1).size() != 12
                || !root.get(0).path("lastupdated").asString().equals(manifest.sourceUpdated()))
            throw new IllegalStateException("Incomplete statistics release");
        var units = Map.of("SP.POP.TOTL", "persons", "SP.DYN.CBRT.IN", "births_per_1000_persons",
                "SP.POP.BRTH.MF", "male_births_per_female_birth", "SP.URB.TOTL.IN.ZS", "percent_population");
        Set<String> keys = new HashSet<>();
        for (var row : root.get(1)) {
            String iso = row.path("countryiso3code").asString();
            String indicator = row.path("indicator").path("id").asString();
            double value = row.path("value").asDouble(-1);
            if (!Set.of("RUS", "JPN", "IND").contains(iso) || !units.containsKey(indicator)
                    || !row.path("date").asString().equals("2023") || !row.path("value").isNumber()
                    || value <= 0 || !keys.add(iso + indicator)
                    || (indicator.equals("SP.URB.TOTL.IN.ZS") && value > 100)
                    || (indicator.equals("SP.POP.BRTH.MF") && (value < 0.8 || value > 1.3)))
                throw new IllegalStateException("Invalid birth statistics observation");
        }
        jdbc.queryForObject("select pg_advisory_xact_lock(748231)", Object.class);
        var existing = jdbc.queryForList("select sha256 from statistical_release where id=?", String.class, manifest.id());
        if (!existing.isEmpty() && !existing.getFirst().equals(hash)) throw new IllegalStateException("Immutable release changed");
        jdbc.update("insert into statistical_release(id, source_url, license, source_updated, retrieved_at, sha256) values(?,?,?,?::date,?::timestamptz,?) on conflict(id) do nothing",
                manifest.id(), manifest.sourceUrl(), manifest.license(), manifest.sourceUpdated(), manifest.retrievedAt(), hash);
        for (var row : root.get(1)) {
            String indicator = row.path("indicator").path("id").asString();
            jdbc.update("insert into statistical_observation values(?,?,?,?,?,?) on conflict do nothing",
                    manifest.id(), row.path("countryiso3code").asString(), indicator, 2023,
                    row.path("value").decimalValue(), units.get(indicator));
        }
        Integer count = jdbc.queryForObject("select count(*) from statistical_observation where release_id=?", Integer.class, manifest.id());
        if (count == null || count != 12) throw new IllegalStateException("Statistics import verification failed");
        expanded.importBundled();
        countries.importBundled();
        return manifest.id();
    }
    public record Manifest(String id, String sourceUrl, String license, String sourceUpdated, String retrievedAt, String sha256) { }
}
