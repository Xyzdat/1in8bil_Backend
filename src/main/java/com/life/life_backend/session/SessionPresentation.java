package com.life.life_backend.session;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.LocalLifeEngine;
import com.life.life_backend.engine.StatChanges;
import com.life.life_backend.engine.adult.AdultLifeEngine;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.engine.local.LocalSimulationEngine;
import com.life.life_backend.web.ApiFailure;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds a response without saving or advancing a life. External providers are never called for previews. */
@Component
class SessionPresentation {
    private final LocalLifeEngine legacy;
    private final LocalSimulationEngine childhood;
    private final AdultLifeEngine adult;

    SessionPresentation(LocalLifeEngine legacy, LocalSimulationEngine childhood, AdultLifeEngine adult) {
        this.legacy = legacy;
        this.childhood = childhood;
        this.adult = adult;
    }

    SessionView view(LifeSession session) {
        var view = session.view();
        var state = view.state();
        var decision = state.pendingDecision();
        if (!session.engineId().equals("local") || decision == null || state.status() == LifeState.Status.COMPLETE) return view;
        var options = decision.options().stream().map(option -> new LifeState.Option(option.id(), option.label(), preview(session, option.id()))).toList();
        var presented = new LifeState(state.age(), state.status(), state.stats(),
                new LifeState.Decision(decision.id(), decision.title(), options), state.history());
        return new SessionView(view.id(), view.character(), view.version(), view.savedAt(), presented, view.provider(), view.simulation(), view.highlights());
    }

    private LifeState.OptionEffects preview(LifeSession session, String optionId) {
        var state = session.checkpoint().state();
        var simulation = session.simulation();
        if (state.stats().health() <= 0) return null;
        try {
            LifeState after;
            List<LocalSimulation.Person> relatives = List.of();
            if (simulation == null) {
                if (!state.pendingDecision().id().equals("first-workshop") || !List.of("paint", "build").contains(optionId)) return null;
                after = legacy.answer(session.checkpoint(), optionId).state();
            } else if (simulation.month() >= 216 && session.adultState() != null) {
                var context = session.localEconomy();
                var result = adult.answer(state, simulation, session.adultState(), context.budget(), context.location(), state.pendingDecision().id(), optionId);
                // An obsolete offer may be cancelled (e.g. a job after retirement), rather than answered.
                if (result.state().history().stream().skip(state.history().size()).noneMatch(event -> event.link() != null
                        && "DECISION".equals(event.link().phase()) && state.pendingDecision().id().equals(event.link().decisionId()))) return null;
                after = result.state();
                relatives = result.simulation().family();
            } else {
                if (simulation.pendingTemplate() == null) return null;
                var result = childhood.answer(state, simulation, state.pendingDecision().id(), optionId);
                after = result.state();
                relatives = result.simulation().family();
            }
            var closeness = new LinkedHashMap<String, Integer>();
            if (simulation != null) for (var person : relatives) {
                var before = simulation.family().stream().filter(old -> old.id().equals(person.id())).findFirst();
                if (before.isPresent() && person.closeness() != before.get().closeness())
                    closeness.put(person.id(), person.closeness() - before.get().closeness());
            }
            return new LifeState.OptionEffects("IMMEDIATE", StatChanges.between(state.stats(), after.stats()), closeness);
        } catch (ApiFailure unavailable) {
            // A purchase/edit may have made a saved offer unaffordable. Reading that save must still work.
            return null;
        }
    }
}
