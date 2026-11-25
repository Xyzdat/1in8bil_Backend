package com.life.life_backend.engine.local;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static com.life.life_backend.engine.local.DeathCauseModel.*;
import static org.assertj.core.api.Assertions.*;

class DeathCauseModelTest {
    private final DeathCauseModel model = new DeathCauseModel();

    @Test void ageRestrictionsHoldAcrossSeedsIncludingInfancyAndExtremeOldAge() {
        for (int months : new int[]{1, 6, 12, 60, 216, 419, 420, 539, 540, 780, 1800}) {
            for (int seed = 0; seed < 100; seed++) {
                var cause = model.determine(seed, "character", months, months, 70, Context.EMPTY);
                assertThat(cause.basis()).isEqualTo("GAME_ASSUMPTION");
                assertThat(cause.label()).isNotBlank();
                if (months > 1) assertThat(cause.code()).isNotEqualTo(Code.PERINATAL_COMPLICATIONS);
                if (months >= 12) assertThat(cause.code()).isNotEqualTo(Code.CONGENITAL_CONDITION);
                if (months < 12) assertThat(cause.code()).isNotIn(Code.ACCIDENT, Code.CANCER);
                if (months < 420) assertThat(cause.code()).isNotEqualTo(Code.HEART_ATTACK);
                if (months < 540) assertThat(cause.code()).isNotEqualTo(Code.STROKE);
                assertThat(cause.conditionId()).isNull();
            }
        }
    }

    @Test void onlyActivePlausibleEpisodesCanExplainTheDeath() {
        var context = new Context(List.of(
                new Condition("acute", "HEALTH_CONDITION", 590, 50, true, false, false),
                new Condition("chronic", "HEALTH_CONDITION", 300, 50, true, true, false),
                new Condition("injury", "INJURY", 599, 50, true, false, false),
                new Condition("recovered", "HEALTH_CONDITION", 590, 50, false, false, false),
                new Condition("future", "HEALTH_CONDITION", 650, 50, true, false, false),
                new Condition("old-injury", "INJURY", 400, 50, true, false, false),
                new Condition("limitation", "INJURY", 599, 50, true, true, false),
                new Condition("mild", "INJURY", 599, 5, true, false, false)), 0);
        var attributed = model.candidates(600, 600, 50, context).stream().filter(c -> c.conditionId() != null).toList();
        assertThat(attributed).extracting(Candidate::conditionId).containsExactly("acute", "chronic", "injury");
        assertThat(attributed).extracting(Candidate::code).containsExactly(Code.ACUTE_DISEASE_COMPLICATIONS,
                Code.CHRONIC_DISEASE_COMPLICATIONS, Code.INJURY_COMPLICATIONS);
    }

    @Test void treatmentAndWorkRiskAffectAttributionWithoutInventingAnExposure() {
        var untreated = new Condition("condition", "HEALTH_CONDITION", 590, 50, true, true, false);
        var treated = new Condition("condition", "HEALTH_CONDITION", 590, 50, true, true, true);
        var a = model.candidates(600, 600, 40, new Context(List.of(untreated), 0));
        var b = model.candidates(600, 600, 40, new Context(List.of(treated), 100));
        assertThat(weight(b, Code.CHRONIC_DISEASE_COMPLICATIONS)).isLessThan(weight(a, Code.CHRONIC_DISEASE_COMPLICATIONS));
        assertThat(weight(b, Code.ACCIDENT)).isGreaterThan(weight(a, Code.ACCIDENT));
        assertThat(model.candidates(60, 60, 70, new Context(List.of(), 100)))
                .isEqualTo(model.candidates(60, 60, 70, Context.EMPTY));
    }

    @Test void attributionIsStableAcrossRestoresAndConditionOrdering() {
        var a = new Condition("a", "HEALTH_CONDITION", 590, 50, true, false, false);
        var b = new Condition("b", "INJURY", 595, 60, true, false, false);
        for (int seed = 0; seed < 100; seed++)
            assertThat(model.determine(seed, "npc-9", 600, 900, 50, new Context(List.of(a, b), 30)))
                    .isEqualTo(model.determine(seed, "npc-9", 600, 900, 50, new Context(List.of(b, a), 30)));
    }

    @Test void oldDeathAndPersonJsonRemainReadableWithoutRetroactiveAttribution() throws Exception {
        var json = new ObjectMapper();
        var legacy = json.readValue("{\"elapsedMonths\":800,\"age\":66,\"cause\":\"UNSPECIFIED\",\"model\":\"UN_WPP_2024_QX\",\"note\":\"old\"}", LocalSimulation.Death.class);
        assertThat(legacy.causeDetails()).isNull();
        var person = json.readValue("{\"id\":\"father\",\"role\":\"father\",\"name\":\"Папа\",\"birthMonth\":-300,\"alive\":false,\"closeness\":50,\"trust\":50,\"diedMonth\":600}", LocalSimulation.Person.class);
        assertThat(person.death()).isNull();
        var cause = model.determine(1, "father", 600, 900, 70, Context.EMPTY);
        var death = new LocalSimulation.Death(600, 75, "UNSPECIFIED", "UN_WPP_2024_QX", "test", cause);
        var updated = new LocalSimulation.Person(person.id(), person.role(), person.name(), -300, false, 50, 50, "MALE", null, 600, death);
        assertThat(json.readValue(json.writeValueAsString(updated), LocalSimulation.Person.class)).isEqualTo(updated);
    }

    private double weight(List<Candidate> candidates, Code code) {
        return candidates.stream().filter(c -> c.code() == code).mapToDouble(Candidate::weight).sum();
    }
}
