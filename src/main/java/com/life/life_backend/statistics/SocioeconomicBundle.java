package com.life.life_backend.statistics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Validates offline source extracts before any database mutation. */
@Component
public class SocioeconomicBundle {
    public static final String FAMILY = "family-housing-2026-11-v1";
    public static final String WORK = "work-income-2026-11-v2";
    public static final String CHINA_OCCUPATIONS = "china-occupations-iloest-2025-v1";
    private final ObjectMapper json;
    private final CountryCatalog countries;

    public SocioeconomicBundle(ObjectMapper json, CountryCatalog countries) {
        this.json = json;
        this.countries = countries;
    }

    public record Observation(String key, String sourceId, String country, String indicator, int year,
                              JsonNode dimensions, BigDecimal value, String quality, JsonNode provenance) { }
    public record Coverage(String country, String indicator, int validCount, int missingCount,
                           int rejectedCount, Integer latestYear) { }
    public record Data(String id, String stage, JsonNode manifest, List<Observation> observations, List<Coverage> coverage) { }

    public Data load(String id) throws Exception {
        if (!Set.of(FAMILY, WORK, CHINA_OCCUPATIONS).contains(id)) throw new IllegalArgumentException("Unknown socioeconomic release");
        var manifest = json.readTree(resource(id, "manifest.json"));
        var codes = new TreeSet<String>();
        manifest.path("countries").forEach(n -> codes.add(n.asString()));
        String stage = id.equals(FAMILY) ? "family-housing" : "work-income";
        require(manifest.path("schemaVersion").asInt() == 1 && manifest.path("id").asString().equals(id)
                && manifest.path("stage").asString().equals(stage)
                && codes.equals(id.equals(CHINA_OCCUPATIONS) ? Set.of("CHN") : countries.codes()), "Invalid manifest");
        for (var source : manifest.path("sources")) {
            require(Set.of("CC-BY-3.0-IGO", "CC-BY-4.0").contains(source.path("license").asString())
                    && source.path("url").asString().startsWith("https://")
                    && !source.path("licenseEvidence").asString().isBlank()
                    && !source.path("attribution").asString().isBlank()
                    && source.path("sha256").asString().matches("[a-f0-9]{64}"), "Missing source provenance/licence");
        }
        List<Observation> rows;
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(verified(id, "observations.json.gz", manifest)))) {
            rows = Arrays.asList(json.readValue(stream, Observation[].class));
        }
        var coverage = Arrays.asList(json.readValue(verified(id, "coverage.json", manifest), Coverage[].class));
        require(rows.size() == manifest.path("rowCount").asInt() && !rows.isEmpty(), "Incomplete observations");
        var keys = new HashSet<String>();
        var counts = new HashMap<String, int[]>();
        var latest = new HashMap<String, Integer>();
        var qualityCounts = new HashMap<String, Integer>();
        var presentCountries = new TreeSet<String>();
        for (var row : rows) {
            var spec = manifest.path("indicators").path(row.indicator());
            require(row.key().matches("[a-f0-9]{64}") && keys.add(row.key()) && codes.contains(row.country())
                    && !spec.isMissingNode() && manifest.path("sources").has(row.sourceId())
                    && spec.path("sourceId").asString().equals(row.sourceId())
                    && row.year() >= manifest.path("minYear").asInt() && row.year() <= manifest.path("maxYear").asInt()
                    && row.dimensions().isObject() && row.provenance().isObject(), "Invalid observation identity");
            int index = switch (row.quality()) {
                case "VALID" -> 0;
                case "MISSING" -> 1;
                case "REJECTED" -> 2;
                default -> throw new IllegalStateException("Invalid quality flag");
            };
            if (index == 0) {
                require(row.value() != null && row.value().compareTo(spec.path("minimum").decimalValue()) >= 0
                        && (spec.path("maximum").isNull() || row.value().compareTo(spec.path("maximum").decimalValue()) <= 0),
                        "Observation outside permitted range");
            } else require(row.value() == null, "Missing or rejected value must remain null");
            String key = row.country() + ":" + row.indicator();
            counts.computeIfAbsent(key, ignored -> new int[3])[index]++;
            qualityCounts.merge(row.quality(), 1, Integer::sum);
            if (index == 0) latest.merge(key, row.year(), Math::max);
            presentCountries.add(row.country());
        }
        require(presentCountries.equals(codes), "Incomplete country coverage");
        for (String quality : List.of("VALID", "MISSING", "REJECTED")) {
            require(qualityCounts.getOrDefault(quality, 0) == manifest.path("qualityCounts").path(quality).asInt(), "Quality count mismatch");
        }
        var coverageKeys = new HashSet<String>();
        for (var c : coverage) {
            String key = c.country() + ":" + c.indicator();
            int[] actual = counts.getOrDefault(key, new int[3]);
            require(codes.contains(c.country()) && manifest.path("indicators").has(c.indicator()) && coverageKeys.add(key)
                    && c.validCount() == actual[0] && c.missingCount() == actual[1] && c.rejectedCount() == actual[2]
                    && Objects.equals(c.latestYear(), latest.get(key)), "Coverage does not match observations");
        }
        require(coverage.size() == codes.size() * manifest.path("indicators").size()
                && coverage.size() == manifest.path("coverageCount").asInt(), "Incomplete coverage matrix");
        return new Data(id, stage, manifest, rows, coverage);
    }

    private byte[] resource(String id, String file) throws Exception {
        try (var stream = new ClassPathResource("statistics/socioeconomic/" + id + "/" + file).getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private byte[] verified(String id, String file, JsonNode manifest) throws Exception {
        byte[] bytes = resource(id, file);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        require(hash.equals(manifest.path("hashes").path(file).asString()), "Checksum mismatch: " + file);
        return bytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
