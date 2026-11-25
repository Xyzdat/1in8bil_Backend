package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsequencesAndDeathTest {
    private LocalSimulation simulation() {
        return new LocalSimulation(123, "childhood-1", LocalSimulation.STATISTICS,
                new LocalSimulation.BirthRequest("RUS", 2026), 50,
                List.of(new LocalSimulation.Person("mother", "mother", "Мама", -300, true, 60, 60),
                        new LocalSimulation.Person("father", "father", "Папа", -336, true, 60, 60)),
                Map.of(), Set.of(), Map.of(), List.of(), "help_choice");
    }
    private LifeState state(LifeState.Decision decision) {
        return new LifeState(4, LifeState.Status.ACTIVE, new LifeState.Stats(70, 70, 50, 80, 50, 0), decision, List.of());
    }
    @Test void legacyDecisionChangesAuraAndOnlyItsParticipantAndSurvivesSerialization() throws Exception {
        var json = new ObjectMapper();
        var engine = new LocalSimulationEngine(new EventCatalog(json), mock(MortalityModel.class));
        var old = simulation();
        var state = state(new LifeState.Decision("old-decision", "Помочь?", List.of(new LifeState.Option("help", "Помочь"))));
        var good = engine.answer(state, old, "old-decision", "help");
        var bad = engine.answer(state, old, "old-decision", "promise_without_help");
        assertThat(good.state().stats().aura()).isEqualTo(2);
        assertThat(bad.state().stats().aura()).isEqualTo(-2);
        assertThat(good.simulation().family().getFirst().closeness()).isEqualTo(60);
        assertThat(good.simulation().family().get(1).closeness()).isEqualTo(64);
        assertThat(bad.simulation().family().get(1).closeness()).isEqualTo(56);
        assertThat(good.state().stats().relationships()).isEqualTo(82);
        assertThat(good.state().history().getLast().consequences().closeness()).containsEntry("father", 4);
        assertThat(good.state().history().getLast().link().sourceId()).isEqualTo("old-decision");
        assertThat(good.simulation().scheduled()).anyMatch(s->"old-decision".equals(s.sourceDecisionId()));
        assertThat(json.readValue(json.writeValueAsString(good.simulation()), LocalSimulation.class)).isEqualTo(good.simulation());
        assertThat(good.simulation().engineVersion()).isEqualTo(LocalSimulation.VERSION);
    }
    @Test void deathEndsLifeMidStepWithoutAnotherDecisionOrScheduledConsequences() throws Exception {
        var mortality = mock(MortalityModel.class);
        when(mortality.dies(any(), any(), any(), eq(0), anyInt(), eq("character"))).thenReturn(true);
        var engine = new LocalSimulationEngine(new EventCatalog(new ObjectMapper()), mortality);
        var ended = engine.step(state(null), simulation());
        assertThat(ended.state().status()).isEqualTo(LifeState.Status.COMPLETE);
        assertThat(ended.simulation().month()).isEqualTo(51);
        assertThat(ended.simulation().death().cause()).isEqualTo("UNSPECIFIED");
        assertThat(ended.simulation().death().causeDetails()).isNotNull();
        assertThat(ended.state().history().getLast().text()).contains(ended.simulation().death().causeDetails().label());
        assertThat(engine.step(state(null), simulation())).isEqualTo(ended);
        assertThat(ended.state().history()).hasSize(1);
        assertThatThrownBy(() -> engine.step(ended.state(), ended.simulation())).hasMessageContaining("ended");
        assertThatThrownBy(() -> engine.answer(ended.state(), ended.simulation(), "x", "x")).hasMessageContaining("ended");
        verify(mortality, times(2)).dies(any(), any(), any(), anyInt(), anyInt(), anyString());
    }
    @Test void parentDeathPreventsFurtherInteractionsAndFreezesAge() throws Exception {
        var mortality = mock(MortalityModel.class);
        when(mortality.dies(any(), any(), any(), anyInt(), anyInt(), eq("father"))).thenReturn(true);
        var engine = new LocalSimulationEngine(new EventCatalog(new ObjectMapper()), mortality);
        var next = engine.step(state(null), simulation());
        assertThat(next.simulation().family().get(1).alive()).isFalse();
        assertThat(next.state().history()).anyMatch(e -> e.id().equals("death-father"));
        var father = next.simulation().view().family().get(1);
        assertThat(father.death().causeDetails()).isNotNull();
        assertThat(father.death().age()).isEqualTo(father.age());
        assertThat(father.death().elapsedMonths()).isEqualTo(51);
        assertThat(next.state().history()).anyMatch(e -> e.id().equals("death-father") && e.text().contains(father.death().causeDetails().label()));
        if (next.state().pendingDecision() != null)
            next = engine.answer(next.state(), next.simulation(), next.state().pendingDecision().id(), next.state().pendingDecision().options().getFirst().id());
        next = engine.step(next.state(), next.simulation());
        assertThat(next.simulation().view().family().get(1).age()).isEqualTo(father.age());
        assertThat(next.simulation().view().family().get(1).death()).isEqualTo(father.death());
        assertThat(next.state().history().stream().filter(e -> e.id().equals("death-father")).count()).isEqualTo(1);
    }
}
