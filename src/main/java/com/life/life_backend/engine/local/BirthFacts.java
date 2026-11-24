package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.CountryReference;
import java.util.*;

/** Immutable birth card with units and explicit provenance; never used as a wallet. */
public record BirthFacts(String countryIso3,String sex,Measure worldBirthProbability,
        Measure selectionProbability,Measure gdpPerCapita,Measure lifeExpectancy,Measure underFiveMortality,
        String currency,List<CountryReference.Religion> countryReligions,List<CountryReference.Language> countryLanguages,
        Description wealth,Description housing,Description familyRelationships,Description familyFaith,Description familyLanguage,
        Map<String,String> sources) {
    public record Measure(double value,String unit,int year,String source,String basis,String note){}
    public record Description(String value,String basis,String source,String note){}
}
