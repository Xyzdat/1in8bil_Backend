package com.life.life_backend;

import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockCookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {"spring.datasource.url=${LIFE_TEST_DB_URL}",
        "spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}", "spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
@AutoConfigureMockMvc
class CountryExpansionIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired CountryExpansionImporter expansion;
    @Autowired CountryCatalog catalog;
    @Autowired CountryReference reference;
    @Autowired CharacterGenerator generator;
    @Autowired LocalSimulationEngine engine;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void importsCompleteReleaseAndKeepsLegacyRowsAndProbabilities() throws Exception {
        importer.importBundled();
        long before = jdbc.queryForObject("select count(*) from mortality_rate", Long.class);
        expansion.importBundled();
        assertThat(jdbc.queryForObject("select count(*) from mortality_rate", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from mortality_rate where release_id=?", Long.class, CountryExpansionImporter.RELEASE)).isEqualTo(559944);
        assertThat(jdbc.queryForObject("select count(*) from mortality_rate where release_id=?", Long.class, ExpandedStatisticsImporter.RELEASE)).isEqualTo(46662);
        assertThat(jdbc.queryForObject("select count(*) from mortality_rate a join mortality_rate b using(iso3,sex,year,age) where a.release_id=? and b.release_id=? and (a.qx<>b.qx or a.mx<>b.mx)", Long.class,
                ExpandedStatisticsImporter.RELEASE, CountryExpansionImporter.RELEASE)).isZero();
        assertThat(reference.all()).hasSize(36);
        double selectionTotal = 0;
        for (var country : reference.all()) {
            var born = generator.generate(13, new LocalSimulation.BirthRequest(country.iso3(), 2026));
            assertThat(born.character().birthplace().countryCode()).isEqualTo(country.iso2());
            assertThat(born.character().birthplace().country()).isEqualTo(catalog.get(country.iso3()).name());
            assertThat(born.simulation().statisticsRelease()).isEqualTo(CountryExpansionImporter.RELEASE);
            assertThat(born.simulation().birthFacts().selectionProbability().value()).isEqualTo(1);
            assertThat(born.simulation().birthFacts().worldBirthProbability().value()).isCloseTo(country.births()/country.worldBirths(), within(1e-12));
            var names = born.character().gender().equals("Мужчина") ? catalog.get(country.iso3()).maleNames() : catalog.get(country.iso3()).femaleNames();
            assertThat(names).contains(born.character().name());
            assertThat(engine.step(born.state(), born.simulation()).simulation().month()).isBetween(1, 2);
            selectionTotal += country.births()/reference.all().stream().mapToDouble(CountryReference.Profile::births).sum();
        }
        assertThat(selectionTotal).isCloseTo(1, within(1e-12));
        var randomLife = generator.generate(13, new LocalSimulation.BirthRequest(null, 2026));
        var selected = reference.get(randomLife.simulation().birthFacts().countryIso3());
        assertThat(randomLife.simulation().birthFacts().selectionProbability().value())
                .isCloseTo(selected.births()/reference.all().stream().mapToDouble(CountryReference.Profile::births).sum(), within(1e-12));

        // A saved v1 life retains its source and can continue using only the original mortality release.
        var born = generator.generate(99, new LocalSimulation.BirthRequest("JPN", 2026));
        var s = born.simulation();
        var legacy = new LocalSimulation(s.seed(), s.engineVersion(), ExpandedStatisticsImporter.RELEASE, s.request(), s.month(),
                s.family(),s.skills(),s.flags(),s.lastEvents(),s.scheduled(),s.pendingTemplate(),s.birthFacts(),null);
        assertThat(engine.step(born.state(),legacy).simulation().statisticsRelease()).isEqualTo(ExpandedStatisticsImporter.RELEASE);
    }

    @Test void httpAcceptsEverySupportedCountryAndRejectsUnsupportedCodes() throws Exception {
        importer.importBundled();
        var cookie = MockCookie.parse(mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getHeader("Set-Cookie"));
        for (String iso : catalog.codes()) {
            String key = UUID.randomUUID().toString();
            String payload = "{\"countryIso3\":\"" + iso + "\"}";
            var response = mvc.perform(post("/api/v1/sessions/generated/local").cookie(cookie).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andReturn();
            var session = json.readTree(response.getResponse().getContentAsString());
            assertThat(session.path("character").path("birthFacts").path("countryIso3").asString()).isEqualTo(iso);
            var repeat = mvc.perform(post("/api/v1/sessions/generated/local").cookie(cookie).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn();
            assertThat(json.readTree(repeat.getResponse().getContentAsString())).isEqualTo(session);
        }
        for (String code : new String[]{"XXX","FRA","CN","chn"}) {
            mvc.perform(post("/api/v1/sessions/generated/local").cookie(cookie).header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"countryIso3\":\""+code+"\"}")).andExpect(status().isBadRequest());
        }
    }
}
