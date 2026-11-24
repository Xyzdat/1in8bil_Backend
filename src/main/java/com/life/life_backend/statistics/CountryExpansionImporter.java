package com.life.life_backend.statistics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Imports a new immutable release, without overwriting v1 references or game saves. */
@Service
public class CountryExpansionImporter {
    public static final String RELEASE = "birth-context-2026-v2";
    public static final int RATES_PER_COUNTRY = 2 * 77 * 101;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CountryCatalog catalog;

    public CountryExpansionImporter(JdbcTemplate jdbc, ObjectMapper json, CountryCatalog catalog) {
        this.jdbc = jdbc; this.json = json; this.catalog = catalog;
    }

    @Transactional
    public void importBundled() throws Exception {
        var manifest = json.readTree(resource("manifest.json"));
        Set<String> codes = new TreeSet<>();
        manifest.path("countries").forEach(c -> codes.add(c.asString()));
        if (!RELEASE.equals(manifest.path("id").asString()) || !codes.equals(catalog.codes())
                || manifest.path("countryCount").asInt() != codes.size()
                || manifest.path("mortalityRows").asInt() != codes.size() * RATES_PER_COUNTRY)
            throw new IllegalStateException("Invalid country expansion manifest");
        var profiles = json.readValue(verified("countries.json", manifest), CountryReference.Profile[].class);
        if (profiles.length != codes.size()) throw new IllegalStateException("Incomplete country profiles");
        var seen = new HashSet<String>();
        for (var p : profiles) {
            if (!codes.contains(p.iso3()) || !seen.add(p.iso3()) || !catalog.get(p.iso3()).iso2().equals(p.iso2())
                    || p.year() != 2026 || p.gdpYear() != 2023 || p.sexRatioYear() != 2026 || p.urbanYear() != 2023
                    || !positive(p.births()) || !positive(p.worldBirths()) || p.worldBirths() <= p.births()
                    || !positive(p.gdpPerCapitaUsd()) || !between(p.lifeExpectancy(), 20, 120)
                    || !between(p.underFiveProbability(), 0, 1) || !between(p.maleFemaleRatio(), 0.8, 1.3)
                    || !between(p.urbanShare(), 0, 1) || !p.currency().matches("[A-Z]{3}")
                    || p.languages().isEmpty() || p.religions().size() != 7
                    || p.languages().stream().anyMatch(l -> !between(l.percent(), 0, 100))
                    || p.religions().stream().anyMatch(r -> !between(r.percent(), 0, 100))
                    || Math.abs(p.religions().stream().mapToDouble(CountryReference.Religion::percent).sum() - 100) > 0.01)
                throw new IllegalStateException("Invalid country profile: " + p.iso3());
        }
        jdbc.queryForObject("select pg_advisory_xact_lock(748233)", Object.class);
        var existing = jdbc.queryForList("select manifest::text from reference_release where id=?", String.class, RELEASE);
        if (!existing.isEmpty() && !json.readTree(existing.getFirst()).equals(manifest))
            throw new IllegalStateException("Immutable reference release changed");
        boolean imported = !existing.isEmpty();
        if (!imported) {
            jdbc.update("insert into reference_release values(?,?::jsonb,current_timestamp)", RELEASE, manifest.toString());
            for (var p : profiles) jdbc.update("insert into country_birth_reference values(?,?,?::jsonb)", RELEASE, p.iso3(), json.writeValueAsString(p));
        }
        // Bound memory and JDBC batch sizes: one country at a time, 1000 inserts per batch.
        for (String iso : codes) {
            byte[] compressed = verified("mortality/" + iso + ".json.gz", manifest);
            if (!imported) {
                try (var stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    insertRates(iso, json.readTree(stream));
                }
            }
        }
        Integer countryCount = jdbc.queryForObject("select count(*) from country_birth_reference where release_id=?", Integer.class, RELEASE);
        var counts = new HashMap<String, Integer>();
        jdbc.query("select iso3,count(*) from mortality_rate where release_id=? group by iso3",
                rs -> { counts.put(rs.getString(1), rs.getInt(2)); }, RELEASE);
        if (!Objects.equals(countryCount, codes.size()) || !counts.keySet().equals(codes)
                || counts.values().stream().anyMatch(n -> n != RATES_PER_COUNTRY))
            throw new IllegalStateException("Country expansion verification failed");
    }

    private void insertRates(String iso, JsonNode rows) {
        var seen = new HashSet<String>();
        var batch = new ArrayList<Object[]>(1000);
        if (rows.size() != RATES_PER_COUNTRY) throw new IllegalStateException("Incomplete mortality: " + iso);
        for (var r : rows) {
            String sex = r.get(1).asString(); int year = r.get(2).asInt(), age = r.get(3).asInt();
            double qx = r.get(4).asDouble(), mx = r.get(5).asDouble();
            if (!iso.equals(r.get(0).asString()) || !Set.of("MALE", "FEMALE").contains(sex)
                    || year < 2024 || year > 2100 || age < 0 || age > 100 || !between(qx, 0, 1)
                    || !Double.isFinite(mx) || mx < 0 || !seen.add(sex + year + ":" + age))
                throw new IllegalStateException("Invalid mortality row: " + iso);
            batch.add(new Object[]{RELEASE, iso, sex, year, age, qx, mx});
            if (batch.size() == 1000) { insertBatch(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) insertBatch(batch);
    }
    private void insertBatch(List<Object[]> batch) {
        jdbc.batchUpdate("insert into mortality_rate values(?,?,?,?,?,?,?)", batch);
    }
    private boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private boolean between(double value, double min, double max) { return Double.isFinite(value) && value >= min && value <= max; }
    private byte[] resource(String file) throws Exception {
        try (var stream = new ClassPathResource("statistics/expanded-v2/" + file).getInputStream()) { return stream.readAllBytes(); }
    }
    private byte[] verified(String file, JsonNode manifest) throws Exception {
        byte[] bytes = resource(file);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals(manifest.path("hashes").path(file).asString())) throw new IllegalStateException("Invalid reference checksum: " + file);
        return bytes;
    }
}
