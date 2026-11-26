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

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class BirthEconomyIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired CharacterGenerator generator;
    @Autowired CountryCatalog countries;
    @Autowired BirthEconomyModel model;
    @Autowired LocalSimulationEngine engine;
    @Autowired ObjectMapper json;

    @Test void generatesWealthAndPresentParentOccupationsForAllCountries() throws Exception {
        importer.importBundled(); int resources=0,occupations=0;
        for(String iso:countries.codes()) {
            var sample=model.sample(iso,2026,1,true,true,1);
            if(sample.resources().relativeToCountryMean()!=null)resources++;
            if(!sample.mother().evidence().isEmpty()||!sample.father().evidence().isEmpty())occupations++;
            for(int seed=1;seed<=30;seed++) {
                var born=generator.generate(seed,new LocalSimulation.BirthRequest(iso,2026));
                var names=born.simulation().naming();
                assertThat(names.modelVersion()).isEqualTo(com.life.life_backend.engine.names.NamingState.VERSION);
                assertThat(born.character().name()).isEqualTo(names.people().get("character").display()).contains(" ");
                assertThat(born.simulation().family()).allSatisfy(person -> {
                    assertThat(person.name()).isEqualTo(names.people().get(person.id()).display()).contains(" ");
                    if (person.role().equals("mother")) assertThat(born.character().background().mother()).startsWith(person.name() + ", ");
                    if (person.role().equals("father")) assertThat(born.character().background().father()).startsWith(person.name() + ", ");
                });
                var economy=born.simulation().calibration().birthEconomy();
                assertThat(economy.modelVersion()).isEqualTo(BirthEconomy.DETAILED_VERSION);
                assertThat(economy.countryIso3()).isEqualTo(iso);
                assertThat(born.details().familyIncomeQuintile()).isEqualTo(economy.resources().quintile());
                assertThat(born.character().background().wealth()).isEqualTo(FamilyResourcesModel.label(economy.resources()));
                assertThat(born.simulation().birthFacts().wealth().basis()).isEqualTo(economy.resources().basis());
                for(String role:List.of("mother","father")) {
                    var expected=role.equals("mother")?economy.mother():economy.father();
                    var detail=role.equals("mother")?born.details().mother():born.details().father();
                    if(expected==null) { assertThat(detail).isNull(); continue; }
                    assertThat(detail.occupation().code()).isEqualTo(expected.code());
                    assertThat(born.simulation().family().stream().filter(p->p.role().equals(role)).findFirst().orElseThrow().occupation()).isEqualTo(expected.label());
                    assertThat(expected.evidence()).allMatch(e->e.year()<=2026);
                    if (!expected.code().endsWith("_X") && !expected.code().equals("UNSPECIFIED")) {
                        assertThat(expected.specialization()).isNotNull();
                        assertThat(expected.label()).contains("игровая специализация");
                    }
                    if (iso.equals("CHN")) {
                        assertThat(expected.evidence()).allMatch(e -> e.year()==2025 && e.indicator().equals("EMP_2EMP_SEX_OCU_NB"));
                        assertThat(expected.note()).contains("Модельная оценка");
                    }
                }
                assertThat(generator.generate(seed,new LocalSimulation.BirthRequest(iso,2026))).isEqualTo(born);
                if(seed==1) {
                    var restored=json.readValue(json.writeValueAsString(born.simulation()),LocalSimulation.class);
                    assertThat(engine.step(born.state(),restored).simulation().calibration().birthEconomy()).isEqualTo(economy);
                    assertThat(engine.step(born.state(),restored).simulation().naming()).isEqualTo(names);
                }
            }
        }
        assertThat(resources).isEqualTo(34); assertThat(occupations).isGreaterThanOrEqualTo(30);
        System.out.println("Birth economy country coverage: resources="+resources+", occupations="+occupations);
        assertThat(model.sample("RUS",2026,1,true,true,1).resources().relativeToCountryMean()).isCloseTo(.385,within(1e-12));
        assertThat(model.sample("IND",2026,1,true,true,1).resources().relativeToCountryMean()).isCloseTo(.555,within(1e-12));
        assertThat(model.sample("SAU",2026,3,true,true,1).mother().code()).isEqualTo("UNSPECIFIED");
    }

    @Test void legacyCalibrationWithoutBirthEconomyIsNotUpgradedWhenRestoredOrStepped() throws Exception {
        importer.importBundled(); var born=generator.generate(42,new LocalSimulation.BirthRequest("RUS",2026));
        ObjectNode stored=json.valueToTree(born.simulation()); ((ObjectNode)stored.get("calibration")).remove("birthEconomy");
        var old=json.treeToValue(stored,LocalSimulation.class);
        assertThat(old.calibration().birthEconomy()).isNull();
        var next=engine.step(born.state(),old);
        assertThat(next.simulation().calibration().birthEconomy()).isNull();
        assertThat(next.simulation().calibration().labour()).isEqualTo(old.calibration().labour());
        assertThat(old.family()).isEqualTo(born.simulation().family());
        assertThat(old.birthFacts()).isEqualTo(born.simulation().birthFacts());
    }

    @Test void savedV1ParentGroupsAreNeverSpecializedOnRestoreOrStep() throws Exception {
        importer.importBundled(); var born=generator.generate(42,new LocalSimulation.BirthRequest("CHN",2026));
        ObjectNode stored=json.valueToTree(born.simulation());
        var economy=(ObjectNode)stored.path("calibration").path("birthEconomy");
        economy.put("modelVersion",BirthEconomy.VERSION);
        for (String parent : List.of("mother","father")) if (economy.path(parent).isObject()) {
            var occupation=(ObjectNode)economy.path(parent);
            occupation.remove("specialization"); occupation.put("label","Работник сельского хозяйства");
            occupation.put("code","OCU_ISCO88_6");
        }
        var old=json.treeToValue(stored,LocalSimulation.class);
        var after=engine.step(born.state(),old).simulation();
        assertThat(after.calibration().birthEconomy()).isEqualTo(old.calibration().birthEconomy());
        assertThat(after.calibration().birthEconomy().modelVersion()).isEqualTo(BirthEconomy.VERSION);
        assertThat(after.family().stream().map(LocalSimulation.Person::occupation).toList())
                .isEqualTo(old.family().stream().map(LocalSimulation.Person::occupation).toList());
        if (after.calibration().birthEconomy().mother()!=null)
            assertThat(after.calibration().birthEconomy().mother().specialization()).isNull();
    }
}
