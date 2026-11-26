package com.life.life_backend.statistics;

import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reads only the pinned, imported releases. No network or implicit import during gameplay. */
@Repository
public class SocioeconomicReference {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, List<Observation>> cache = new ConcurrentHashMap<>();

    public SocioeconomicReference(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public List<Observation> observations(String release, String country) {
        if (!Set.of(SocioeconomicBundle.FAMILY, SocioeconomicBundle.WORK, SocioeconomicBundle.CHINA_OCCUPATIONS).contains(release))
            throw new IllegalArgumentException("Unsupported socioeconomic release");
        return cache.computeIfAbsent(release + ":" + country, ignored -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from socioeconomic_release where id=?)", Boolean.class, release)))
                throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "STATISTICS_NOT_READY", "Import the pinned socioeconomic statistics before creating a new life.");
            return List.copyOf(jdbc.query("""
                    select row_key,indicator,year,dimensions,value,provenance,source_id
                    from socioeconomic_observation where release_id=? and country_iso3=? and quality='VALID'
                    order by year desc, source_id, row_key
                    """, (rs, row) -> new Observation(rs.getString(1), rs.getString(2), rs.getInt(3),
                    json.readTree(rs.getString(4)), rs.getDouble(5), json.readTree(rs.getString(6)), rs.getString(7)), release, country));
        });
    }

    public record Observation(String key, String indicator, int year, JsonNode dimensions, double value,
                              JsonNode provenance, String sourceId) {
        public String dimension(String key) { return dimensions.path(key).asString(""); }
        public SocioeconomicCalibration.Evidence evidence() {
            return new SocioeconomicCalibration.Evidence(key, indicator, year, sourceId, value);
        }
    }
}
