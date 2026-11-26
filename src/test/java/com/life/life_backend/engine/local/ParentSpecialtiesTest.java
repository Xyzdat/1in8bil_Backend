package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ParentSpecialtiesTest {
    final ObjectMapper json = new ObjectMapper();
    final ParentSpecialties catalog = new ParentSpecialties(json);
    ParentSpecialtiesTest() throws Exception { }

    @Test void everyKnownGroupGetsSpecificReproducibleDetailWithoutChangingStatisticalEvidence() {
        for (String major : List.of("0","1","2","3","4","5","6","7","8","9","96")) {
            var group = group(major);
            var labels = new HashSet<String>();
            for (int seed = 0; seed < 100; seed++) {
                var specific = catalog.specify(group, seed);
                assertThat(specific).isEqualTo(catalog.specify(group, seed));
                assertThat(specific.code()).isEqualTo(group.code());
                assertThat(specific.evidence()).isEqualTo(group.evidence());
                assertThat(specific.probability()).isEqualTo(group.probability());
                assertThat(specific.label()).contains("игровая специализация");
                assertThat(specific.specialization().basis()).isEqualTo("GAME_ASSUMPTION");
                assertThat(specific.specialization().conditionalProbability()).isBetween(0d, 1d);
                labels.add(specific.specialization().code());
                assertThat(json.readValue(json.writeValueAsString(specific), BirthEconomy.Occupation.class)).isEqualTo(specific);
            }
            assertThat(labels).hasSizeGreaterThan(1);
        }
    }

    @Test void unknownGroupsRemainUnknownAndOldOccupationJsonIsNotUpgraded() {
        assertThat(catalog.specify(null, 1)).isNull();
        assertThat(catalog.specify(group("X"), 1)).isEqualTo(group("X"));
        var old = json.readValue("""
                {"code":"OCU_ISCO88_6","label":"Работник сельского хозяйства","basis":"SAMPLED_PROXY","sampledSex":"SEX_F", "probability":0.68,"classifiedShare":1,"evidence":[],"note":"old"}
                """, BirthEconomy.Occupation.class);
        assertThat(old.specialization()).isNull();
        assertThat(old.label()).isEqualTo("Работник сельского хозяйства");
    }

    @Test void combinedIloGroupIsNotSplitIntoFakeStatisticalGroups() {
        var rows = List.of(row("TOTAL",100),row("96",70),row("2",30));
        var distribution = ParentOccupationModel.distribution(rows,2025,"SEX_F","EMP_2EMP_SEX_OCU_NB");
        assertThat(distribution.weights()).containsOnlyKeys("OCU_ISCO08_96","OCU_ISCO08_2");
        assertThat(distribution.weights()).containsEntry("OCU_ISCO08_96",.7);
        assertThat(distribution.note()).contains("Модельная оценка", "объединяет 6 и 9");
        assertThat(ParentOccupationModel.distribution(rows,2024,"SEX_F","EMP_2EMP_SEX_OCU_NB").weights()).isEmpty();
        var mixed = new ArrayList<>(rows); mixed.add(row("6",10));
        assertThat(ParentOccupationModel.distribution(mixed,2025,"SEX_F","EMP_2EMP_SEX_OCU_NB").weights()).isEmpty();
        assertThat(ParentOccupationModel.distribution(rows,2025,"SEX_F").weights()).isEmpty();
    }

    private BirthEconomy.Occupation group(String major) {
        return new BirthEconomy.Occupation("OCU_ISCO08_"+major,"Группа", "SAMPLED_PROXY","SEX_F",.3,1,
                List.of(new SocioeconomicCalibration.Evidence("test","test",2025,"source",100)),"Source");
    }
    private SocioeconomicReference.Observation row(String code, double value) {
        return new SocioeconomicReference.Observation(code,"EMP_2EMP_SEX_OCU_NB",2025,
                json.valueToTree(Map.of("sex","SEX_F","source","XA:2070","classif1","OCU_ISCO08_"+code)),
                value,json.createObjectNode(),"iloest-china");
    }
}
