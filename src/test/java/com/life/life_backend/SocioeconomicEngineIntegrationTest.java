package com.life.life_backend;

import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL", matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class SocioeconomicEngineIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired CharacterGenerator generator;
    @Autowired CountryCatalog countries;
    @Autowired LocalSimulationEngine engine;
    @Autowired ObjectMapper json;
    @Autowired LabourCalibrationFactory labour;

    @Test void familyAndHousingUseDatabaseForAllCountriesAndSurviveRestore() throws Exception {
        importer.importBundled();
        int single = 0, both = 0;
        for (String iso : countries.codes()) for (int seed = 1; seed <= 30; seed++) {
            var born = generator.generate(seed, new LocalSimulation.BirthRequest(iso, 2026));
            var sim = born.simulation(); var h = sim.calibration().family(); var d = born.details();
            assertThat(h.evidence()).isNotEmpty().allMatch(e -> e.year() <= 2026);
            assertThat(d.household().size()).isGreaterThanOrEqualTo(sim.family().size() + 1);
            assertThat(d.electricityAtHome()).isEqualTo(h.electricity());
            assertThat(d.homeOwned()).isNull();
            assertThat(d.fatherPresent()).isEqualTo(h.fatherPresent());
            assertThat(d.mother() != null).isEqualTo(h.motherPresent());
            assertThat(d.father() != null).isEqualTo(h.fatherPresent());
            assertThat(sim.family().stream().filter(p -> p.role().equals("father")).count()).isEqualTo(h.fatherPresent() ? 1 : 0);
            assertThat(born.character().background().housing()).contains("электричеств");
            assertThat(sim.birthFacts().housing().basis()).isEqualTo("SAMPLED_PROXY");
            if (h.motherPresent() && h.fatherPresent()) both++; else single++;
            assertThat(generator.generate(seed, new LocalSimulation.BirthRequest(iso, 2026))).isEqualTo(born);
            if (seed == 1) {
                var restored = json.readValue(json.writeValueAsString(sim), LocalSimulation.class);
                assertThat(engine.step(born.state(), restored)).isEqualTo(engine.step(born.state(), sim));
                var next = engine.step(born.state(), restored);
                assertThat(next.simulation().calibration()).isEqualTo(sim.calibration());
            }
        }
        assertThat(single).isPositive(); assertThat(both).isPositive();
    }

    @Test void oldSaveWithoutCalibrationStaysLegacyAfterRestoreAndStep() throws Exception {
        importer.importBundled();
        var born = generator.generate(719, new LocalSimulation.BirthRequest("RUS", 2026));
        ObjectNode stored = json.valueToTree(born.simulation()); stored.remove("calibration");
        var legacy = json.treeToValue(stored, LocalSimulation.class);
        assertThat(legacy.calibration()).isNull();
        var next = engine.step(born.state(), legacy);
        assertThat(next.simulation().calibration()).isNull();
        assertThat(legacy.family()).isEqualTo(born.simulation().family());
        assertThat(legacy.withBirthFacts(legacy.birthFacts()).calibration()).isNull();
    }

    @Test void wageCalibrationUsesRealCohortsAndExplicitFallbacksForAllCountries() throws Exception {
        importer.importBundled();
        var snapshot = labour.snapshot(2026);
        assertThat(snapshot).hasSize(36);
        for (var country : snapshot.values()) {
            assertThat(country.occupationPayRatios().values()).allMatch(v -> Double.isFinite(v) && v >= .4 && v <= 3);
            assertThat(country.evidence()).allMatch(e -> e.year() <= 2026);
            assertThat(country.assumptions()).isNotEmpty();
        }
        assertThat(snapshot.get("RU").occupationPayRatios().get("2")).isCloseTo(63573d/57240d,within(.0000001));
        assertThat(snapshot.get("JP").occupationPayRatios()).doesNotContainKey("2");
        assertThat(snapshot.get("IR").occupationPayRatios()).isEmpty();
        assertThat(snapshot.values().stream().filter(l -> l.occupationPayRatios().keySet().stream().anyMatch(k -> k.matches("[1-9]"))).count()).isGreaterThanOrEqualTo(20);
        assertThat(labour.snapshot(2023).values()).allMatch(l -> l.evidence().stream().allMatch(e -> e.year() <= 2023));
        System.out.println("Calibrated wage country coverage: occupation=" + snapshot.values().stream().filter(l -> l.occupationPayRatios().keySet().stream().anyMatch(k -> k.matches("[1-9]"))).count()
                + ", median/mean=" + snapshot.values().stream().filter(l -> l.occupationPayRatios().containsKey("TYPICAL")).count());
    }
}
