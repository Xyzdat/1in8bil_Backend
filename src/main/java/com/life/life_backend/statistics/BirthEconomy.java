package com.life.life_backend.statistics;

import java.util.List;

/** Immutable record of the original birth draw; manual edits never rewrite this evidence. */
public record BirthEconomy(String modelVersion, String countryIso3, int referenceYear,
                           Resources resources, Occupation mother, Occupation father) {
    public static final String VERSION = "birth-economy-1";
    public static final String DETAILED_VERSION = "birth-economy-2";
    public record Resources(int quintile, Double relativeToCountryMean, String basis,
                            List<SocioeconomicCalibration.Evidence> evidence, String note) {
        public Resources { evidence = List.copyOf(evidence); }
    }
    public record Occupation(String code, String label, String basis, String sampledSex,
                             double probability, double classifiedShare,
                             List<SocioeconomicCalibration.Evidence> evidence, String note, Specialization specialization) {
        public Occupation { evidence = List.copyOf(evidence); }
        public Occupation(String code, String label, String basis, String sampledSex, double probability, double classifiedShare,
                          List<SocioeconomicCalibration.Evidence> evidence, String note) {
            this(code, label, basis, sampledSex, probability, classifiedShare, evidence, note, null);
        }
    }
    public record Specialization(String modelVersion, String code, String label, String basis, double conditionalProbability) { }
}
