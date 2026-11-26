package com.life.life_backend.engine.local;

import com.life.life_backend.engine.*;
import com.life.life_backend.statistics.*;
import org.springframework.stereotype.Component;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import java.util.*;

@Component
public class CharacterGenerator {
    private final CountryCatalog catalog;
    private final CountryReference reference;
    private final BirthContextFactory context;
    private final FamilyHousingModel households;
    private final LabourCalibrationFactory labour;
    private final BirthEconomyModel birthEconomy;
    public CharacterGenerator(CountryCatalog catalog, CountryReference reference, BirthContextFactory context, FamilyHousingModel households, LabourCalibrationFactory labour, BirthEconomyModel birthEconomy) {
        this.catalog = catalog; this.reference = reference; this.context = context; this.households = households; this.labour = labour; this.birthEconomy = birthEconomy;
    }

    public Birth generate(long seed, LocalSimulation.BirthRequest request) {
        var random = new SplittableRandom(seed);
        var profiles = reference.all();
        var profile = profiles.getLast();
        if (request.countryIso3() != null) {
            profile = profiles.stream().filter(c -> c.iso3().equals(request.countryIso3())).findFirst()
                    .orElseThrow(() -> new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_COUNTRY", "Supported: " + String.join(", ", catalog.codes())));
        } else {
            double draw = random.nextDouble(profiles.stream().mapToDouble(CountryReference.Profile::births).sum());
            for (var candidate : profiles) {
                draw -= candidate.births();
                if (draw < 0) { profile = candidate; break; }
            }
        }
        String iso = profile.iso3();
        var country = catalog.get(iso);
        boolean male = random.nextDouble() < profile.maleFemaleRatio() / (1 + profile.maleFemaleRatio());
        boolean urban = random.nextDouble() < profile.urbanShare();
        // Population quintiles have equal mass. Birth weighting and parent ages remain assumptions.
        int quintile = random.nextInt(1, 6);
        int motherAge = random.nextInt(22, 36), fatherAge = motherAge + random.nextInt(0, 6);
        var household = households.sample(iso, request.birthYear(), motherAge, fatherAge, seed);
        var economy = birthEconomy.sample(iso, request.birthYear(), quintile, household.motherPresent(), household.fatherPresent(), seed);
        var family = new ArrayList<LocalSimulation.Person>();
        String motherJob = economy.mother() == null ? null : economy.mother().label();
        String fatherJob = economy.father() == null ? null : economy.father().label();
        if (household.motherPresent()) family.add(new LocalSimulation.Person("mother", "mother", name(iso, false, random), -motherAge * 12, true, random.nextInt(50, 81), 65, "FEMALE", motherJob));
        if (household.fatherPresent()) family.add(new LocalSimulation.Person("father", "father", name(iso, true, random), -fatherAge * 12, true, random.nextInt(50, 81), 65, "MALE", fatherJob));
        int siblingCount = household.siblings();
        var siblings = new ArrayList<BirthDetails.Sibling>();
        for (int i = 0; i < siblingCount; i++) {
            int age = 2 + i * 2; boolean boy = random.nextBoolean();
            siblings.add(new BirthDetails.Sibling(age, boy ? BirthDetails.Sex.MALE : BirthDetails.Sex.FEMALE, true, true));
            family.add(new LocalSimulation.Person("sibling-" + i, boy ? "brother" : "sister", name(iso, boy, random), -age * 12, true, random.nextInt(45, 81), 60, boy ? "MALE" : "FEMALE", "Ребёнок"));
        }
        int bonds = (int) family.stream().mapToInt(LocalSimulation.Person::closeness).average().orElseThrow();
        String wealth = FamilyResourcesModel.label(economy.resources());
        String housing = households.housing(urban ? "Квартира" : "Сельский дом", household);
        String relationships = bonds >= 65 ? "Тёплые отношения" : "Сдержанные отношения";
        var religion = weighted(profile.religions(), CountryReference.Religion::percent, random);
        var language = weighted(profile.languages(), CountryReference.Language::percent, random);
        String faith = switch (religion.code()) {
            case "Christians" -> "Христианство"; case "Muslims" -> "Ислам"; case "Hindus" -> "Индуизм";
            case "Buddhists" -> "Буддизм"; case "Jews" -> "Иудаизм"; case "Unaffiliated" -> "Без религиозной принадлежности";
            default -> "Другие религии";
        };
        // Keep the original given-name draw and all demographic/stat draws in the same order.
        String givenName = name(iso, male, random);
        var naming = com.life.life_backend.engine.names.PersonalNames.birth(catalog.names(), iso, givenName,
                male ? "MALE" : "FEMALE", family, seed);
        family.replaceAll(person -> new LocalSimulation.Person(person.id(), person.role(), naming.people().get(person.id()).display(),
                person.birthMonth(), person.alive(), person.closeness(), person.trust(), person.sex(), person.occupation(), person.diedMonth(), person.death()));
        String familyLabel = household.motherPresent() && household.fatherPresent() ? "Мать и отец" : household.motherPresent() ? "Мать" : "Отец";
        var background = new CharacterProfile.Background(familyLabel, Integer.toString(siblingCount), null,
                housing, relationships, parentLabel(family, "father"), parentLabel(family, "mother"),
                wealth, faith, language.code());
        var character = new CharacterProfile(naming.people().get("character").display(), male ? "Мужчина" : "Женщина", request.birthYear(),
                new CharacterProfile.Birthplace(country.iso2(), country.name(), null, null), background);
        var details = new BirthDetails(urban ? BirthDetails.PlaceType.URBAN : BirthDetails.PlaceType.RURAL,
                household.motherPresent() && household.fatherPresent() ? BirthDetails.FamilyType.MOTHER_AND_FATHER : null,
                siblingCount, quintile, household.fatherPresent(),
                household.motherPresent() ? new BirthDetails.Parent(motherAge, new BirthDetails.Occupation(economy.mother().code(), motherJob), true, true) : null,
                household.fatherPresent() ? new BirthDetails.Parent(fatherAge, new BirthDetails.Occupation(economy.father().code(), fatherJob), true, true) : null, siblings,
                new BirthDetails.Household(household.householdSize(), household.motherPresent(), household.fatherPresent(), false, false, false, siblingCount, 0, 0),
                null, household.electricity(), religion.code(), null, null, List.of());
        var facts = context.create(iso, male ? "MALE" : "FEMALE", request.countryIso3() != null,
                wealth, housing, relationships, faith, language.code());
        facts = context.withHousehold(facts, household);
        facts = context.withEconomy(facts, economy);
        var calibration = new SocioeconomicCalibration(SocioeconomicCalibration.VERSION, SocioeconomicBundle.FAMILY,
                SocioeconomicBundle.WORK, household, labour.snapshot(request.birthYear()), economy);
        var simulation = new LocalSimulation(seed, LocalSimulation.VERSION, CountryExpansionImporter.RELEASE, request, 0,
                family, Map.of(), Set.of(urban ? "urban" : "rural"), Map.of(), List.of(), null, facts, null, calibration, naming);
        var state = new LifeState(0, LifeState.Status.ACTIVE,
                new LifeState.Stats(random.nextInt(65, 91), 70, 50, bonds, random.nextInt(35, 76), 0), null,
                List.of(new LifeState.HistoryEvent("birth", 0, "Рождение", "Ты родился в стране «" + country.name()
                        + "». Семья: " + familyLabel + "." + (siblingCount == 0 ? "" : " В семье есть старшие дети: " + siblingCount + "."))));
        return new Birth(character, details, state, simulation);
    }

    private String parentLabel(List<LocalSimulation.Person> family, String role) {
        return family.stream().filter(p -> p.role().equals(role)).map(p -> p.name() + ", " + p.occupation()).findFirst().orElse(null);
    }

    private static <T> T weighted(List<T> rows, java.util.function.ToDoubleFunction<T> weight, SplittableRandom random) {
        double draw = random.nextDouble(rows.stream().mapToDouble(weight).sum());
        for (var row : rows) { draw -= weight.applyAsDouble(row); if (draw < 0) return row; }
        return rows.getLast();
    }
    private String name(String iso, boolean male, SplittableRandom random) {
        var country = catalog.get(iso);
        var names = male ? country.maleNames() : country.femaleNames();
        return names.get(random.nextInt(names.size()));
    }
    public record Birth(CharacterProfile character, BirthDetails details, LifeState state, LocalSimulation simulation) { }
}
