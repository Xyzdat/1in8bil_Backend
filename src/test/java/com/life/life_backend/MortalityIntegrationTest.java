package com.life.life_backend;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {"spring.datasource.url=${LIFE_TEST_DB_URL}",
        "spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}", "spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class MortalityIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired MortalityModel mortality;
    @Autowired CharacterGenerator generator;
    @Autowired LocalSimulationEngine engine;
    @Autowired JdbcTemplate jdbc;

    @Test void sixSlotsReproduceAnnualQxAndOpenAgeDoesNotForceDeath() throws Exception {
        importer.importBundled();
        for (String iso : new String[]{"RUS", "JPN", "IND"}) for (String sex : new String[]{"MALE", "FEMALE"}) {
            double qx = jdbc.queryForObject("select qx from mortality_rate where release_id=? and iso3=? and sex=? and year=2026 and age=50", Double.class, CountryExpansionImporter.RELEASE, iso, sex);
            double slot = mortality.probability(iso, sex, 2026, 50, 1.0/6, 1);
            assertThat(1 - Math.pow(1 - slot, 6)).isCloseTo(qx, within(1e-12));
            assertThat(mortality.probability(iso, sex, 2026, 50, 1.0/6, 2)).isGreaterThan(slot);
            assertThat(mortality.probability(iso, sex, 2150, 120, 1.0/6, 1)).isBetween(0.0, 0.999999);
            assertThat(mortality.probability(iso, sex, 2150, 120, 1.0/6, 1))
                    .isEqualTo(mortality.probability(iso, sex, 2100, 100, 1.0/6, 1));
        }
    }

    @Test void realSavedModelCanRunThroughAllAgesUntilDynamicDeath() throws Exception {
        importer.importBundled();
        for (String iso : new String[]{"RUS", "JPN", "IND"}) for (long seed = 10; seed < 14; seed++) {
            var born = generator.generate(seed, new LocalSimulation.BirthRequest(iso, 2026));
            assertThat(born.simulation().birthFacts().worldBirthProbability().value()).isBetween(0.0, 1.0);
            assertThat(born.simulation().birthFacts().selectionProbability().value()).isEqualTo(1);
            var next = new LocalSimulationEngine.Transition(born.state(), born.simulation());
            for (int step = 0; step < 1200 && next.state().status() == LifeState.Status.ACTIVE; step++) {
                next = engine.step(next.state(), next.simulation());
                var decision = next.state().pendingDecision();
                if (decision != null) next = engine.answer(next.state(), next.simulation(), decision.id(), decision.options().getFirst().id());
            }
            assertThat(next.state().status()).as(iso + ":" + seed).isEqualTo(LifeState.Status.COMPLETE);
            assertThat(next.state().history().stream().filter(e -> e.id().equals("death-character")).count()).isEqualTo(1);
            assertThat(next.simulation().scheduled()).isEmpty();
        }
    }
}
