package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.economy.LocalBudget;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalSimulationEngineTest {
    private final LocalSimulationEngine engine = new LocalSimulationEngine(new EventCatalog(new ObjectMapper()), mock(MortalityModel.class));
    LocalSimulationEngineTest() throws Exception { }

    @Test
    void childhoodIsDeterministicAndHasNoEarlyChoicesOrSevenYearCutoff() {
        for (long seed = 0; seed < 100; seed++) {
            var first = run(seed, "draw");
            assertThat(first).isEqualTo(run(seed, "draw"));
            assertThat(first.state().age()).isEqualTo(8);
            assertThat(first.state().status()).isEqualTo(LifeState.Status.ACTIVE);
            assertThat(first.state().history().stream().map(LifeState.HistoryEvent::id)).doesNotHaveDuplicates();
            assertThat(first.simulation().family().getFirst().birthMonth()).isEqualTo(-300);
            assertThat(first.simulation().family().getFirst().closeness()).isBetween(65, 100);
            for (int age = 0; age < 8; age++) {
                int year = age;
                assertThat(first.state().history().stream().filter(e -> e.age() == year && !e.id().endsWith("-answer")).count()).isEqualTo(6);
                assertThat(first.state().history().stream().filter(e -> e.age() == year && !e.id().endsWith("-answer"))
                        .map(LifeState.HistoryEvent::title)).doesNotHaveDuplicates();
            }
            assertThat(first.state().history().stream().filter(e -> e.id().endsWith("-answer")).count()).isGreaterThanOrEqualTo(8);
        }
    }

    @Test
    void choicesChangeSkillsAndLaterEvents() {
        var drawing = run(5, "draw");
        var building = run(5, "blocks");
        assertThat(drawing.simulation().skills()).containsKey("drawing");
        assertThat(building.simulation().skills()).doesNotContainKey("drawing");
        assertThat(drawing.state().history()).anyMatch(e -> e.id().contains("drawing_return"));
        assertThat(building.state().history()).anyMatch(e -> e.id().contains("construction_return"));
    }

    @Test
    void unrelatedEventsPreserveExistingPurchaseBonuses() {
        var s = new LocalSimulation(1, LocalSimulation.VERSION, LocalSimulation.STATISTICS,
                new LocalSimulation.BirthRequest("RUS", 2026), 40,
                List.of(new LocalSimulation.Person("mother", "mother", "Мама", -300, true, 65, 65)),
                Map.of(), Set.of(), Map.of(), List.of(), null);
        var stats = new LifeState.Stats(70, 70, 50, 90, 50, 0);
        var unrelated = EffectApplier.apply(stats, s, new EventCatalog.Effect(1, 0, 0, 0, null, 0, null, null, 0), null);
        assertThat(unrelated.stats().relationships()).isEqualTo(90);
        var family = EffectApplier.apply(stats, s, new EventCatalog.Effect(0, 0, 0, 2, null, 0, null, null, 0), "mother");
        assertThat(family.stats().relationships()).isEqualTo(92);
        assertThat(family.simulation().family().getFirst().closeness()).isEqualTo(67);
    }

    @Test
    void monthlyBudgetDoesNotDependOnStepSize() {
        var chunks = LocalBudget.atMonth(null, 0);
        for (int month = 2; month <= 96; month += 2) chunks = LocalBudget.atMonth(chunks, month);
        assertThat(chunks).isEqualTo(LocalBudget.atMonth(null, 96));
        assertThat(LocalBudget.atMonth(chunks, 96)).isEqualTo(chunks);
    }

    private LocalSimulationEngine.Transition run(long seed, String drawingChoice) {
        var simulation = new LocalSimulation(seed, LocalSimulation.VERSION, LocalSimulation.STATISTICS,
                new LocalSimulation.BirthRequest("RUS", 2026), 0,
                List.of(new LocalSimulation.Person("mother", "mother", "Мама", -300, true, 65, 65),
                        new LocalSimulation.Person("father", "father", "Папа", -336, true, 65, 65)),
                Map.of(), Set.of("urban"), Map.of(), List.of(), null);
        var state = new LifeState(0, LifeState.Status.ACTIVE, new LifeState.Stats(70, 70, 50, 65, 50, 0), null, List.of());
        var transition = new LocalSimulationEngine.Transition(state, simulation);
        for (int step = 0; step < 48; step++) {
            transition = engine.step(transition.state(), transition.simulation());
            var decision = transition.state().pendingDecision();
            if (decision != null) {
                assertThat(transition.state().age()).isGreaterThanOrEqualTo(3);
                String choice = decision.options().stream().anyMatch(o -> o.id().equals(drawingChoice)) ? drawingChoice : decision.options().getFirst().id();
                transition = engine.answer(transition.state(), transition.simulation(), decision.id(), choice);
            }
        }
        return transition;
    }
}
