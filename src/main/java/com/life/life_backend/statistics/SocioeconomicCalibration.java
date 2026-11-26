package com.life.life_backend.statistics;

import java.util.*;

/** Server-only frozen calibration. Absence means legacy rules, even after a restore or upgrade. */
public record SocioeconomicCalibration(String modelVersion, String familyRelease, String workRelease,
                                      Family family, Map<String, Labour> labour, BirthEconomy birthEconomy) {
    public static final String VERSION = "socioeconomic-1";
    public SocioeconomicCalibration { labour = Map.copyOf(labour); }
    public SocioeconomicCalibration(String modelVersion, String familyRelease, String workRelease, Family family, Map<String, Labour> labour) {
        this(modelVersion, familyRelease, workRelease, family, labour, null);
    }
    public record Evidence(String rowKey, String indicator, int year, String source, double value) { }
    public record Family(String countryIso3, boolean motherPresent, boolean fatherPresent, int siblings,
                         int householdSize, Boolean electricity, Boolean water, Boolean sanitation,
                         List<Evidence> evidence, List<String> assumptions) {
        public Family { evidence = List.copyOf(evidence); assumptions = List.copyOf(assumptions); }
    }
    public record Labour(Map<String, Double> occupationPayRatios, List<Evidence> evidence, List<String> assumptions) {
        public Labour { occupationPayRatios = Map.copyOf(occupationPayRatios); evidence = List.copyOf(evidence); assumptions = List.copyOf(assumptions); }
    }
}
