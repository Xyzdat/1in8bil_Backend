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
    public CharacterGenerator(CountryCatalog catalog, CountryReference reference, BirthContextFactory context) {
        this.catalog = catalog; this.reference = reference; this.context = context;
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
        // Explicit game assumptions, not observed joint household distributions.
        int quintile = random.nextInt(1, 6);
        int motherAge = random.nextInt(22, 36), fatherAge = motherAge + random.nextInt(0, 6);
        var family = new ArrayList<LocalSimulation.Person>();
        String motherJob = occupation(random, quintile, urban), fatherJob = occupation(random, quintile, urban);
        family.add(new LocalSimulation.Person("mother", "mother", name(iso, false, random), -motherAge * 12, true, random.nextInt(50, 81), 65, "FEMALE", motherJob));
        family.add(new LocalSimulation.Person("father", "father", name(iso, true, random), -fatherAge * 12, true, random.nextInt(50, 81), 65, "MALE", fatherJob));
        int siblingCount = random.nextInt(0, Math.min(4, (motherAge - 18) / 2 + 1));
        var siblings = new ArrayList<BirthDetails.Sibling>();
        for (int i = 0; i < siblingCount; i++) {
            int age = 2 + i * 2; boolean boy = random.nextBoolean();
            siblings.add(new BirthDetails.Sibling(age, boy ? BirthDetails.Sex.MALE : BirthDetails.Sex.FEMALE, true, true));
            family.add(new LocalSimulation.Person("sibling-" + i, boy ? "brother" : "sister", name(iso, boy, random), -age * 12, true, random.nextInt(45, 81), 60, boy ? "MALE" : "FEMALE", "Ребёнок"));
        }
        int bonds = (int) family.stream().mapToInt(LocalSimulation.Person::closeness).average().orElseThrow();
        String wealth = List.of("Низкий достаток", "Ниже среднего", "Средний достаток", "Выше среднего", "Высокий достаток").get(quintile - 1);
        String housing = urban ? "Квартира" : "Дом в сельской местности";
        String relationships = bonds >= 65 ? "Тёплые отношения" : "Сдержанные отношения";
        var religion = weighted(profile.religions(), CountryReference.Religion::percent, random);
        var language = weighted(profile.languages(), CountryReference.Language::percent, random);
        String faith = switch (religion.code()) {
            case "Christians" -> "Христианство"; case "Muslims" -> "Ислам"; case "Hindus" -> "Индуизм";
            case "Buddhists" -> "Буддизм"; case "Jews" -> "Иудаизм"; case "Unaffiliated" -> "Без религиозной принадлежности";
            default -> "Другие религии";
        };
        var background = new CharacterProfile.Background("Мать и отец", Integer.toString(siblingCount), null,
                housing, relationships, family.get(1).name() + ", " + fatherJob, family.getFirst().name() + ", " + motherJob,
                wealth, faith, language.code());
        var character = new CharacterProfile(name(iso, male, random), male ? "Мужчина" : "Женщина", request.birthYear(),
                new CharacterProfile.Birthplace(country.iso2(), country.name(), null, null), background);
        var details = new BirthDetails(urban ? BirthDetails.PlaceType.URBAN : BirthDetails.PlaceType.RURAL,
                BirthDetails.FamilyType.MOTHER_AND_FATHER, siblingCount, quintile, true,
                new BirthDetails.Parent(motherAge, new BirthDetails.Occupation("local", motherJob), true, true),
                new BirthDetails.Parent(fatherAge, new BirthDetails.Occupation("local", fatherJob), true, true), siblings,
                new BirthDetails.Household(3 + siblingCount, true, true, false, false, false, siblingCount, 0, 0),
                null, null, religion.code(), null, null, List.of());
        var facts = context.create(iso, male ? "MALE" : "FEMALE", request.countryIso3() != null,
                wealth, housing, relationships, faith, language.code());
        var simulation = new LocalSimulation(seed, LocalSimulation.VERSION, CountryExpansionImporter.RELEASE, request, 0,
                family, Map.of(), Set.of(urban ? "urban" : "rural"), Map.of(), List.of(), null, facts, null);
        var state = new LifeState(0, LifeState.Status.ACTIVE,
                new LifeState.Stats(random.nextInt(65, 91), 70, 50, bonds, random.nextInt(35, 76), 0), null,
                List.of(new LifeState.HistoryEvent("birth", 0, "Рождение", "Ты родился в стране «" + country.name()
                        + "». Рядом мама и папа." + (siblingCount == 0 ? "" : " В семье есть старшие дети: " + siblingCount + "."))));
        return new Birth(character, details, state, simulation);
    }

    private static <T> T weighted(List<T> rows, java.util.function.ToDoubleFunction<T> weight, SplittableRandom random) {
        double draw = random.nextDouble(rows.stream().mapToDouble(weight).sum());
        for (var row : rows) { draw -= weight.applyAsDouble(row); if (draw < 0) return row; }
        return rows.getLast();
    }
    private static String occupation(SplittableRandom random, int quintile, boolean urban) {
        String[] jobs = quintile >= 4 ? new String[]{"Инженер", "Врач", "Руководитель"}
                : urban ? new String[]{"Продавец", "Водитель", "Учитель", "Работник производства"}
                : new String[]{"Работник сельского хозяйства", "Учитель", "Ремонтник"};
        return jobs[random.nextInt(jobs.length)];
    }
    private String name(String iso, boolean male, SplittableRandom random) {
        var country = catalog.get(iso);
        var names = male ? country.maleNames() : country.femaleNames();
        return names.get(random.nextInt(names.size()));
    }
    public record Birth(CharacterProfile character, BirthDetails details, LifeState state, LocalSimulation simulation) { }
}
