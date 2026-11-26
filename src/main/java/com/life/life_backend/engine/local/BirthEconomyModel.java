package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BirthEconomyModel {
    private final SocioeconomicReference reference;
    private final ParentSpecialties specialties;
    private final Map<String, Profile> cache = new ConcurrentHashMap<>();
    public BirthEconomyModel(SocioeconomicReference reference, ParentSpecialties specialties) { this.reference = reference; this.specialties = specialties; }
    private record Profile(List<SocioeconomicReference.Observation> resources,
                           ParentOccupationModel.Distribution mother, ParentOccupationModel.Distribution father) { }

    public BirthEconomy sample(String country, int year, int quintile, boolean mother, boolean father, long seed) {
        var profile = cache.computeIfAbsent(country + ":" + year, ignored -> {
            var rows = reference.observations(SocioeconomicBundle.WORK, country);
            var occupations = country.equals("CHN") ? reference.observations(SocioeconomicBundle.CHINA_OCCUPATIONS, country) : rows;
            String indicator = country.equals("CHN") ? "EMP_2EMP_SEX_OCU_NB" : "EMP_TEMP_SEX_OCU_NB";
            return new Profile(FamilyResourcesModel.distribution(rows, year),
                    ParentOccupationModel.distribution(occupations, year, "SEX_F", indicator), ParentOccupationModel.distribution(occupations, year, "SEX_M", indicator));
        });
        return new BirthEconomy(BirthEconomy.DETAILED_VERSION, country, year, FamilyResourcesModel.sample(profile.resources(), quintile),
                mother ? specialties.specify(ParentOccupationModel.sample(profile.mother(), new SplittableRandom(seed ^ 0x5214BA12L)), seed ^ 0xAB1045L) : null,
                father ? specialties.specify(ParentOccupationModel.sample(profile.father(), new SplittableRandom(seed ^ 0x5315CA23L)), seed ^ 0xBA2156L) : null);
    }
}
