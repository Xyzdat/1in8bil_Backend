package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.CountryReference;
import com.life.life_backend.statistics.CountryCatalog;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BirthContextFactory {
    private static final String UN = "UN WPP 2024, medium projection";
    private final CountryReference reference;
    private final CountryCatalog catalog;
    public BirthContextFactory(CountryReference reference, CountryCatalog catalog) { this.reference = reference; this.catalog = catalog; }

    public BirthFacts create(String iso, String sex, boolean selectedCountry, String wealth, String housing,
            String relationships, String faith, String language) {
        return create(iso, sex, selectedCountry, wealth, housing, relationships, faith, language, reference.all());
    }
    private BirthFacts create(String iso, String sex, boolean selectedCountry, String wealth, String housing,
            String relationships, String faith, String language, List<CountryReference.Profile> universe) {
        var p = reference.get(iso);
        double total = universe.stream().mapToDouble(CountryReference.Profile::births).sum();
        return new BirthFacts(iso, sex,
                new BirthFacts.Measure(p.births() / p.worldBirths(), "probability", 2026, UN, "PROJECTION", "Доля рождений страны среди всех рождений мира."),
                new BirthFacts.Measure(selectedCountry ? 1 : p.births() / total, "probability", 2026, UN, "GAME_SELECTION", "Выбор среди " + universe.size() + " стран релиза; при явном выборе страны равен 1."),
                new BirthFacts.Measure(p.gdpPerCapitaUsd(), "USD_CURRENT", p.gdpYear(), "World Bank WDI: NY.GDP.PCAP.CD", "STATISTIC", "ВВП на душу населения, текущие доллары США; не доход семьи и не PPP."),
                new BirthFacts.Measure(p.lifeExpectancy(), "years", 2026, UN, "PROJECTION", "Ожидаемая продолжительность жизни при рождении, оба пола; не дата смерти персонажа."),
                new BirthFacts.Measure(p.underFiveProbability(), "probability", 2026, UN, "PROJECTION", "Вероятность умереть до пятилетия, оба пола."),
                p.currency(), p.religions(), p.languages(), model(wealth), model(housing), model(relationships),
                new BirthFacts.Description(faith, "SAMPLED_PROXY", "Pew Research Center, 2020 population shares", "Выборка по населению страны, не статистика семей новорождённых."),
                new BirthFacts.Description(language, "SAMPLED_PROXY", "Unicode CLDR 48 territoryInfo", "Взвешенная игровая выборка по владению языками, включая вторые языки; не статистика домашнего языка."),
                Map.of("demography", "https://population.un.org/wpp/",
                        "gdp", "https://data.worldbank.org/indicator/NY.GDP.PCAP.CD",
                        "religions", "https://www.pewresearch.org/feature/religious-composition-by-country-2010-2070/",
                        "languagesAndCurrency", "https://github.com/unicode-org/cldr-json/tree/48.0.0"));
    }

    public LocalSimulation enrichLegacy(LocalSimulation simulation, String iso2, String gender) {
        if (simulation.birthFacts() != null) return simulation;
        String iso = catalog.iso3(iso2);
        // Do not regenerate the family, seed, decisions or past events of an existing save.
        return simulation.withBirthFacts(create(iso, "Мужчина".equals(gender) ? "MALE" : "FEMALE",
                simulation.request().countryIso3() != null, null, null, null, null, null,
                reference.all().stream().filter(p -> Set.of("IND", "JPN", "RUS").contains(p.iso3())).toList()));
    }

    private BirthFacts.Description model(String value) {
        return new BirthFacts.Description(value, "GAME_ASSUMPTION", "family-model-2",
                "Игровая модель; распределение доходов, состава семьи, возраста и профессий родителей, жилья и близости не является импортированной статистикой.");
    }
}
