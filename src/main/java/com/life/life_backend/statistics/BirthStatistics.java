package com.life.life_backend.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

@Repository
public class BirthStatistics {
    private final JdbcTemplate jdbc;
    public BirthStatistics(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Country> countries(String release) {
        var result = new ArrayList<Country>();
        for (String iso : List.of("IND", "JPN", "RUS")) {
            Map<String, Double> values = new HashMap<>();
            jdbc.query("select indicator, value from statistical_observation where release_id=? and country_iso3=? and year=2023",
                    rs -> { values.put(rs.getString(1), rs.getDouble(2)); }, release, iso);
            if (values.size() != 4) throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE,
                    "STATISTICS_NOT_READY", "Import the verified birth statistics release before generating a life.");
            result.add(new Country(iso, switch (iso) { case "RUS" -> "RU"; case "JPN" -> "JP"; default -> "IN"; },
                    switch (iso) { case "RUS" -> "Россия"; case "JPN" -> "Япония"; default -> "Индия"; },
                    values.get("SP.POP.TOTL") * values.get("SP.DYN.CBRT.IN") / 1000,
                    values.get("SP.POP.BRTH.MF"), values.get("SP.URB.TOTL.IN.ZS") / 100));
        }
        return List.copyOf(result);
    }
    public record Country(String iso3, String iso2, String name, double estimatedBirths,
                          double maleFemaleRatio, double urbanShare) { }
}
