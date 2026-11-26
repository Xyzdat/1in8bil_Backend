package com.life.life_backend.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Explicit import only: no external requests and no updates to existing lives or releases. */
@Service
public class SocioeconomicImporter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SocioeconomicBundle bundles;

    public SocioeconomicImporter(JdbcTemplate jdbc, ObjectMapper json, SocioeconomicBundle bundles) {
        this.jdbc = jdbc;
        this.json = json;
        this.bundles = bundles;
    }

    @Transactional(rollbackFor = Exception.class)
    public Report importBundled(String releaseId) throws Exception {
        var data = bundles.load(releaseId);
        jdbc.queryForObject("select pg_advisory_xact_lock(748234)", Object.class);
        var existing = jdbc.queryForList("select manifest::text from socioeconomic_release where id=?", String.class, releaseId);
        if (existing.isEmpty()) {
            jdbc.update("insert into socioeconomic_release(id,stage,manifest) values(?,?,?::jsonb)",
                    releaseId, data.stage(), data.manifest().toString());
            jdbc.batchUpdate("""
                insert into socioeconomic_observation
                    (release_id,row_key,source_id,country_iso3,indicator,year,dimensions,value,quality,provenance)
                values(?,?,?,?,?,?,?::jsonb,?,?,?::jsonb)
                """, data.observations(), 500, (ps, r) -> {
                ps.setString(1, releaseId); ps.setString(2, r.key()); ps.setString(3, r.sourceId());
                ps.setString(4, r.country()); ps.setString(5, r.indicator()); ps.setInt(6, r.year());
                ps.setString(7, r.dimensions().toString()); ps.setBigDecimal(8, r.value());
                ps.setString(9, r.quality()); ps.setString(10, r.provenance().toString());
            });
            jdbc.batchUpdate("insert into socioeconomic_coverage values(?,?,?,?,?,?,?)", data.coverage(), 500, (ps, c) -> {
                ps.setString(1, releaseId); ps.setString(2, c.country()); ps.setString(3, c.indicator());
                ps.setInt(4, c.validCount()); ps.setInt(5, c.missingCount()); ps.setInt(6, c.rejectedCount());
                ps.setObject(7, c.latestYear());
            });
        } else if (!json.readTree(existing.getFirst()).equals(data.manifest())) {
            throw new IllegalStateException("Immutable socioeconomic manifest changed");
        }
        verify(data); // A retry verifies stored values, not just row counts.
        return new Report(releaseId, data.observations().size(), data.coverage().size(), existing.isEmpty());
    }

    private void verify(SocioeconomicBundle.Data data) {
        var expected = new HashMap<String, SocioeconomicBundle.Observation>();
        data.observations().forEach(r -> expected.put(r.key(), r));
        jdbc.query("select * from socioeconomic_observation where release_id=?", rs -> {
            var row = expected.remove(rs.getString("row_key"));
            var value = rs.getBigDecimal("value");
            boolean sameValue = row != null && (row.value() == null ? value == null
                    : value != null && row.value().compareTo(value) == 0);
            if (!sameValue || !row.sourceId().equals(rs.getString("source_id"))
                    || !row.country().equals(rs.getString("country_iso3")) || !row.indicator().equals(rs.getString("indicator"))
                    || row.year() != rs.getInt("year") || !row.quality().equals(rs.getString("quality"))
                    || !row.dimensions().equals(json.readTree(rs.getString("dimensions")))
                    || !row.provenance().equals(json.readTree(rs.getString("provenance"))))
                throw new IllegalStateException("Stored socioeconomic observation differs from release");
        }, data.id());
        if (!expected.isEmpty()) throw new IllegalStateException("Stored socioeconomic observations are missing");
        var expectedCoverage = new HashSet<>(data.coverage());
        jdbc.query("select * from socioeconomic_coverage where release_id=?", rs -> {
            var c = new SocioeconomicBundle.Coverage(rs.getString("country_iso3"), rs.getString("indicator"),
                    rs.getInt("valid_count"), rs.getInt("missing_count"), rs.getInt("rejected_count"),
                    rs.getObject("latest_year", Integer.class));
            if (!expectedCoverage.remove(c)) throw new IllegalStateException("Stored coverage differs from release");
        }, data.id());
        if (!expectedCoverage.isEmpty()) throw new IllegalStateException("Stored coverage is missing");
    }

    public record Report(String release, int observations, int coverageEntries, boolean newlyImported) { }
}
