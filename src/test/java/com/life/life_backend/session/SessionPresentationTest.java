package com.life.life_backend.session;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.*;
import com.life.life_backend.engine.adult.*;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionPresentationTest {
    final ObjectMapper json = new ObjectMapper();
    final LocalLifeEngine legacy = new LocalLifeEngine();
    final LocalSimulationEngine childhood;
    final AdultLifeEngine adult;
    final SessionPresentation presentation;

    SessionPresentationTest() throws Exception {
        childhood = new LocalSimulationEngine(new EventCatalog(json), mock(MortalityModel.class));
        var spending = mock(SpendingService.class);
        when(spending.shop(any(), eq("property"))).thenReturn(new FinanceView.Shop(1,30,FinanceView.Currency.RUB,
                null,"","","",BigDecimal.ZERO,null,List.of(),List.of()));
        adult = new AdultLifeEngine(new AdultStoryCatalog(json), childhood, mock(CountryReference.class), new CountryCatalog(json), spending);
        presentation = new SessionPresentation(legacy, childhood, adult);
    }

    @Test void childhoodPreviewMatchesClampedAnswerAndDoesNotApplyOrRevealFollowUps() throws Exception {
        var sim = simulation(38, "drawing_choice");
        var state = state(3, 99, "local-38-drawing_choice", List.of(new LifeState.Option("draw", "Рисовать"), new LifeState.Option("blocks", "Строить")));
        var session = session(state, "local"); session.initializeSimulation(sim);
        var saved = session.view();
        var view = presentation.view(session);
        assertThat(view.state().pendingDecision().options().getFirst().effects().stats()).containsExactlyEntriesOf(Map.of("relationships", 1));
        assertThat(view.state().pendingDecision().options().getFirst().effects().closeness()).containsExactlyEntriesOf(Map.of("mother", 1));
        assertThat(view.state().pendingDecision().options().get(1).effects().stats()).isEmpty();
        for (var option : view.state().pendingDecision().options()) {
            var answer = childhood.answer(state, sim, state.pendingDecision().id(), option.id());
            assertThat(option.effects().stats()).isEqualTo(StatChanges.between(state.stats(), answer.state().stats()));
        }
        assertThat(presentation.view(session)).isEqualTo(view);
        assertThat(session.view()).isEqualTo(saved);
        assertThat(session.simulation()).isEqualTo(sim);
        assertThat(sim.scheduled()).isEmpty();
        assertThat(json.writeValueAsString(view)).doesNotContain("drawing_return", "construction_return");
    }

    @Test void adultPreviewMatchesEveryAnswerIncludingFatalHealthAndLeavesAllStateUntouched() throws Exception {
        var session = adultSession(4, 0);
        var saved = session.view();
        var context = session.localEconomy();
        var adultBefore = json.writeValueAsString(session.adultState());
        var simBefore = json.writeValueAsString(session.simulation());
        var view = presentation.view(session);
        for (var option : view.state().pendingDecision().options()) {
            var actual = adult.answer(session.checkpoint().state(), session.simulation(), session.adultState(),
                    context.budget(), context.location(), "burn-CHOICE", option.id());
            assertThat(option.effects().stats()).isEqualTo(StatChanges.between(saved.state().stats(), actual.state().stats()));
            if (option.id().equals("push")) {
                assertThat(option.effects().stats()).containsEntry("health", -4);
                assertThat(actual.state().status()).isEqualTo(LifeState.Status.COMPLETE);
            }
        }
        assertThat(presentation.view(session)).isEqualTo(view);
        assertThat(session.view()).isEqualTo(saved);
        assertThat(json.writeValueAsString(session.adultState())).isEqualTo(adultBefore);
        assertThat(json.writeValueAsString(session.simulation())).isEqualTo(simBefore);
        assertThat(session.localEconomy().budget()).isEqualTo(context.budget());
        assertThat(session.checkpoint().state().status()).isEqualTo(LifeState.Status.ACTIVE);
    }

    @Test void auraIsIncludedAndClampedIndependentlyOfFamilyCloseness() throws Exception {
        var definition = new EventCatalog(json).get("truth_choice");
        var options = definition.options().stream().map(choice -> new LifeState.Option(choice.id(), choice.label())).toList();
        var state = state(3, 80, "local-38-truth_choice", options);
        var session = session(state, "local"); session.initializeSimulation(simulation(38, "truth_choice"));
        var positive = definition.options().stream().filter(choice -> choice.effect().aura() > 0).findFirst().orElseThrow();
        var effect = presentation.view(session).state().pendingDecision().options().stream()
                .filter(option -> option.id().equals(positive.id())).findFirst().orElseThrow().effects();
        assertThat(effect.stats()).containsEntry("aura", 1);
        assertThat(session.checkpoint().state().stats().aura()).isEqualTo(99);
    }

    @Test void unaffordableSavedOptionHasUnknownEffectsButSaveStillLoads() {
        var session = adultSession(90, 999_999_999);
        var view = presentation.view(session);
        assertThat(view.state().pendingDecision().options().getFirst().effects()).isNull();
        assertThat(view.state().pendingDecision().options().get(1).effects()).isNotNull();
    }

    @Test void externalEffectsAreUnknownAndFullSavedNameIsNotSplit() {
        var session = session(state(3, 70, "external", List.of(new LifeState.Option("read", "Read"))), "1in8b");
        var view = presentation.view(session);
        assertThat(view.character().name()).isEqualTo("Анна Мария Иванова");
        assertThat(session.summary().name()).isEqualTo(view.character().name());
        assertThat(view.state().pendingDecision().options().getFirst().effects()).isNull();
        assertThat(view.state().history().getFirst().importance()).isNull();
        assertThat(session.view()).isEqualTo(view);
    }

    @Test void oldJsonWithoutPresentationFieldsStillLoadsAndLegacyWorkshopHasPreview() {
        var restored = json.readValue("""
                {"age":3,"status":"ACTIVE","stats":{"health":70,"happiness":99,"intelligence":98,"relationships":70,"appearance":null,"aura":null},
                 "pendingDecision":{"id":"first-workshop","title":"Мастерская","options":[{"id":"paint","label":"Рисовать"}]},
                 "history":[{"id":"birth","age":0,"title":"Рождение","text":"Начало"}]}
                """, LifeState.class);
        assertThat(restored.history().getFirst().importance()).isNull();
        assertThat(restored.pendingDecision().options().getFirst().effects()).isNull();
        var view = presentation.view(session(restored, "local"));
        assertThat(view.state().pendingDecision().options().getFirst().effects().stats()).containsExactlyEntriesOf(Map.of("happiness", 1));
    }

    private LifeSession adultSession(int health, long restCost) {
        var state = state(30, health, "burn-CHOICE", List.of(new LifeState.Option("rest", "Отдохнуть"), new LifeState.Option("push", "Работать больше")));
        var sim = simulation(360, null);
        var data = new AdultState(AdultState.VERSION, 360, 0, new AdultState.Traits(50,50,50,50),
                new AdultState.Education("SECONDARY",null,null,null,"COMPLETED"),
                new AdultState.Career("EMPLOYED","Мастер","Услуги","Компания","Специалист",2_000_000,300,60,1,70,70,10,0,List.of()),
                null,Map.of(),List.of(),List.of(),List.of(),
                List.of(new AdultState.Story("burn","burnout","CHOICE","ACTIVE",360,360,null,List.of(),Map.of("cost:rest",Long.toString(restCost)),List.of("CHOICE"))),
                List.of(),new AdultState.Pending("burn","CHOICE",List.of("rest","push")),"FAMILY_HOME",Set.of(),Map.of());
        var session = session(state, "local"); session.initializeSimulation(sim);
        session.applyAdult(new AdultLifeEngine.Transition(state,sim,data,new LocalBudget(360,1_000_000,List.of(),List.of())));
        return session;
    }

    private LocalSimulation simulation(int month, String pending) {
        return new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),month,
                List.of(new LocalSimulation.Person("mother","mother","Анна Иванова",-300,true,99,99)),Map.of(),Set.of(),Map.of(),List.of(),pending);
    }

    private LifeState state(int age, int health, String question, List<LifeState.Option> options) {
        return new LifeState(age,LifeState.Status.ACTIVE,new LifeState.Stats(health,99,98,99,50,99),
                new LifeState.Decision(question,"Вопрос",options),List.of(new LifeState.HistoryEvent("birth",0,"Рождение","Начало")));
    }

    private LifeSession session(LifeState state, String engine) {
        var profile = new CharacterProfile("Анна Мария Иванова","Женщина",2026,new CharacterProfile.Birthplace("RU","Россия",null,null),null);
        return new LifeSession(UUID.randomUUID(),UUID.randomUUID(),new LifeSession.Setup(profile,null,null),engine,new LifeEngine.Checkpoint(state,null));
    }
}
