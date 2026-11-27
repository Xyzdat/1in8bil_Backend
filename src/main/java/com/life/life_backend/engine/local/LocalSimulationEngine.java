package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LocalSimulationEngine {
    private final EventCatalog catalog;
    private final MortalityModel mortality;
    private final DeathCauseModel deathCauses = new DeathCauseModel();
    public LocalSimulationEngine(EventCatalog catalog, MortalityModel mortality) {
        this.catalog = catalog; this.mortality = mortality;
    }

    public Transition step(LifeState state, LocalSimulation current) {
        var checked = checkMortality(state, current, Map.of());
        if (checked.state().status() == LifeState.Status.COMPLETE) return checked;
        var focused=com.life.life_backend.engine.focus.FocusEffects.settle(checked.state(),checked.simulation(),null,current.month()+2);
        return nextEvent(focused.state(), focused.simulation());
    }

    /** One mortality draw per elapsed interval, shared by childhood and adult simulation. */
    public Transition checkMortality(LifeState state, LocalSimulation current, Map<String, Integer> npcHealth) {
        return checkMortality(state, current, npcHealth, DeathCauseModel.Context.EMPTY);
    }

    public Transition checkMortality(LifeState state, LocalSimulation current, Map<String, Integer> npcHealth,
                                     DeathCauseModel.Context heroContext) {
        requireVersion(current);
        if (state.status() == LifeState.Status.COMPLETE) throw conflict("SESSION_COMPLETE", "This life has ended.");
        if (state.stats().health() <= 0) return completeIfHealthDepleted(state, current, heroContext);
        if (state.pendingDecision() != null) throw conflict("DECISION_REQUIRED", "Answer the pending decision first.");
        String iso = current.birthFacts() == null ? current.request().countryIso3() : current.birthFacts().countryIso3();
        String sex = current.birthFacts() == null ? "MALE" : current.birthFacts().sex();
        if (mortality.dies(current, iso, sex, 0, state.stats().health(), "character")) return endLife(state, current, heroContext);
        var deaths = new ArrayList<LifeState.HistoryEvent>();
        var family = new ArrayList<LocalSimulation.Person>();
        for (var person : current.family()) {
            String personSex = person.sex() == null ? (person.role().equals("father") ? "MALE" : "FEMALE") : person.sex();
            if (person.alive() && mortality.dies(current, iso, personSex, person.birthMonth(), npcHealth.getOrDefault(person.id(), 70), person.id())) {
                var death = death(current, person.id(), person.birthMonth(), npcHealth.getOrDefault(person.id(), 70), DeathCauseModel.Context.EMPTY);
                family.add(new LocalSimulation.Person(person.id(), person.role(), person.name(), person.birthMonth(), false,
                        person.closeness(), person.trust(), personSex, person.occupation(), death.elapsedMonths(), death));
                deaths.add(new LifeState.HistoryEvent("death-" + person.id(), death.elapsedMonths() / 12,
                        "Утрата близкого", person.name() + " умирает. Причина смерти: " + death.causeDetails().label()
                                + ". В памяти остаются ваши совместные события."));
            } else family.add(person);
        }
        if (!deaths.isEmpty()) {
            current = current.evolve(current.month(), family, current.skills(), current.flags(), current.lastEvents(), current.scheduled(), null, null);
            var history = new ArrayList<>(state.history()); history.addAll(deaths);
            var stats = state.stats();
            state = new LifeState(state.age(), state.status(), new LifeState.Stats(stats.health(), Math.max(0, stats.happiness() - 15 * deaths.size()),
                    stats.intelligence(), stats.relationships(), stats.appearance(), stats.aura()), null, history);
        }
        return new Transition(state, current);
    }

    private Transition nextEvent(LifeState state, LocalSimulation current) {
        int month = current.month() + 2;
        var queue = new ArrayList<>(current.scheduled());
        EventCatalog.Definition event = null;
        String sourceDecisionId = null;
        for (var scheduled : current.scheduled()) {
            if (scheduled.dueMonth() <= month) {
                queue.remove(scheduled);
                var candidate = catalog.get(scheduled.templateId());
                if (eligible(candidate, current, month)) { event = candidate; sourceDecisionId = scheduled.sourceDecisionId(); break; }
            }
        }
        if (event == null) {
            var candidates = catalog.all().stream().filter(d -> !d.followUpOnly() && eligible(d, current, month)).toList();
            boolean choiceDue = month >= 38 && DecisionPacing.allowed(state, current, month)
                    && new SplittableRandom(current.seed() ^ (0xD1342543DE82EF95L * month)).nextDouble()
                        < DecisionPacing.childhoodProbability(state, current);
            var choices = candidates.stream().filter(d -> d.options() != null).toList();
            if (choiceDue && !choices.isEmpty()) candidates = choices;
            else candidates = candidates.stream().filter(d -> d.options() == null).toList();
            var unseen = candidates.stream().filter(d -> !current.lastEvents().containsKey(d.id())).toList();
            if (!unseen.isEmpty()) candidates = unseen;
            if (candidates.isEmpty()) throw conflict("CONTENT_UNAVAILABLE", "No eligible event. The life has not ended.");
            var random = new SplittableRandom(current.seed() ^ (0x9E3779B97F4A7C15L * month));
            int draw = random.nextInt(candidates.stream().mapToInt(d -> com.life.life_backend.engine.focus.YearFocus.childhoodWeight(current,d.id(),d.weight())).sum());
            for (var candidate : candidates) { draw -= com.life.life_backend.engine.focus.YearFocus.childhoodWeight(current,candidate.id(),candidate.weight()); if (draw < 0) { event = candidate; break; } }
        }
        var lastEvents = new HashMap<>(current.lastEvents());
        lastEvents.put(event.id(), month);
        int occurrenceCount = lastEvents.getOrDefault("#count:" + event.id(), 0);
        lastEvents.put("#count:" + event.id(), occurrenceCount + 1);
        if (event.options() != null) lastEvents.put("#decision", month);
        var simulation = copy(current, month, lastEvents, queue, event.options() == null ? null : event.id());
        String occurrence = "local-" + month + "-" + event.id();
        var history = new ArrayList<>(state.history());
        // The final slot belongs to the year that has just elapsed; age increases at its boundary.
        int eventAge = (month - 1) / 12;
        String text = event.text();
        if (event.variants() != null && !event.variants().isEmpty() && occurrenceCount > 0)
            text = event.variants().get((occurrenceCount - 1) % event.variants().size());
        if (event.options() != null) {
            simulation = DecisionPacing.recordOffer(state, simulation, month);
            history.add(new LifeState.HistoryEvent(occurrence, eventAge, event.title(), render(text, simulation, event.participantRole()), null,
                    childLink(event, simulation, occurrence, "OFFER", occurrence, null, null)));
            var decision = new LifeState.Decision(occurrence, event.title(), event.options().stream()
                    .map(c -> new LifeState.Option(c.id(), c.label())).toList());
            return new Transition(new LifeState(month / 12, state.status(), state.stats(), decision, history), simulation);
        }
        var applied = EffectApplier.apply(state.stats(), simulation, event.effect(), event.participantRole(), sourceDecisionId);
        history.add(new LifeState.HistoryEvent(occurrence, eventAge, event.title(), render(text, simulation, event.participantRole()),
                consequences(state.stats(), simulation, applied), sourceDecisionId == null ? null
                        : childLink(event, simulation, sourceDecisionId, "OUTCOME", null, null, null)));
        return completeIfHealthDepleted(new LifeState(month / 12, state.status(), applied.stats(), null, history), applied.simulation(), DeathCauseModel.Context.EMPTY);
    }

    public Transition answer(LifeState state, LocalSimulation current, String decisionId, String optionId) {
        requireVersion(current);
        if (state.status() == LifeState.Status.COMPLETE) throw conflict("SESSION_COMPLETE", "This life has ended.");
        if (state.stats().health() <= 0) return completeIfHealthDepleted(state, current, DeathCauseModel.Context.EMPTY);
        if (state.pendingDecision() == null || !state.pendingDecision().id().equals(decisionId))
            throw conflict("DECISION_CHANGED", "This decision is not pending.");
        var definition = catalog.get(current.pendingTemplate());
        var option = definition.options().stream().filter(c -> c.id().equals(optionId)).findFirst()
                .orElseThrow(() -> new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_OPTION", "Unknown option."));
        var simulation = copy(current, current.month(), current.lastEvents(), current.scheduled(), null);
        var applied = EffectApplier.apply(state.stats(), simulation, option.effect(), definition.participantRole(), decisionId);
        var history = new ArrayList<>(state.history());
        history.add(new LifeState.HistoryEvent(decisionId + "-answer", (current.month() - 1) / 12, "Твоё решение",
                render(option.text(), simulation, definition.participantRole()), consequences(state.stats(), simulation, applied),
                childLink(definition, simulation, decisionId, "DECISION", decisionId, option, state.pendingDecision())));
        return completeIfHealthDepleted(new LifeState(state.age(), state.status(), applied.stats(), null, history), applied.simulation(), DeathCauseModel.Context.EMPTY);
    }
    private boolean eligible(EventCatalog.Definition d, LocalSimulation s, int month) {
        Integer last = s.lastEvents().get(d.id());
        return month >= d.minMonth() && month <= d.maxMonth()
                && (last == null || (!d.once() && month - last >= d.cooldownMonths() && (last - 1) / 12 != (month - 1) / 12))
                && (d.requiredFlag() == null || s.flags().contains(d.requiredFlag()))
                && (d.excludedFlag() == null || !s.flags().contains(d.excludedFlag()))
                && (d.requiredSkill() == null || s.skills().getOrDefault(d.requiredSkill(), 0) >= d.minSkill())
                && (d.participantRole() == null || s.family().stream().anyMatch(p -> p.alive()
                    && p.role().equals(d.participantRole()) && p.closeness() >= d.minCloseness()));
    }
    private com.life.life_backend.highlights.EventLink childLink(EventCatalog.Definition definition, LocalSimulation sim,
            String sourceId, String phase, String decisionId, EventCatalog.Choice choice, LifeState.Decision offered) {
        var person = sim.participant(definition.participantRole());
        String domain = definition.id().startsWith("drawing") || definition.id().startsWith("construction") ? "INTERESTS" : "RELATIONSHIPS";
        return new com.life.life_backend.highlights.EventLink(sourceId, definition.id(), domain, phase, sim.month(), decisionId,
                choice == null ? null : choice.id(), choice == null ? null : choice.label(), choice == null ? null : choice.id(),
                offered == null ? List.of() : offered.options(), person == null ? List.of() : List.of(person.id()), List.of());
    }
    private String render(String text, LocalSimulation s, String role) {
        var participant = s.participant(role);
        String person = participant == null ? "" : participant.name();
        return text.replace("{person}", person);
    }
    private LocalSimulation copy(LocalSimulation s, int month, Map<String, Integer> last, List<LocalSimulation.Scheduled> queue, String pending) {
        return s.evolve(month, s.family(), s.skills(), s.flags(), last, queue, pending, s.death());
    }
    private void requireVersion(LocalSimulation s) {
        if (!Set.of(LocalSimulation.VERSION, "childhood-1").contains(s.engineVersion())) throw conflict("ENGINE_VERSION_UNAVAILABLE", "Unsupported local engine version.");
    }
    private LifeState.Consequences consequences(LifeState.Stats before, LocalSimulation old, EffectApplier.Result after) {
        var changes = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < old.family().size(); i++) {
            int delta = after.simulation().family().get(i).closeness() - old.family().get(i).closeness();
            if (delta != 0) changes.put(old.family().get(i).id(), delta);
        }
        return new LifeState.Consequences((after.stats().aura() == null ? 0 : after.stats().aura()) - (before.aura() == null ? 0 : before.aura()), changes);
    }
    private LocalSimulation.Death death(LocalSimulation current, String personId, int birthMonth, int health, DeathCauseModel.Context context) {
        int month = current.month() + (health <= 0 ? 0 : 1);
        int ageMonths = Math.max(0, month - birthMonth);
        var details = health <= 0 ? deathCauses.depletedHealth(month, context)
                : deathCauses.determine(current.seed(), personId, month, ageMonths, health, context);
        // Keep the legacy field compatible with existing strict frontend decoders; use causeDetails for the specific cause.
        return new LocalSimulation.Death(month, ageMonths / 12, "UNSPECIFIED", "UN_WPP_2024_QX",
                health <= 0 ? "Здоровье достигло нуля: жизнь завершена по игровому правилу, без случайной проверки смертности."
                : "Смертность — по таблицам ООН. Конкретная причина выбрана игровой моделью, а не статистикой причин смерти.", details);
    }

    public Transition completeIfHealthDepleted(LifeState state, LocalSimulation current, DeathCauseModel.Context context) {
        if (state.status() == LifeState.Status.COMPLETE || state.stats().health() > 0) return new Transition(state, current);
        return endLife(state, current, context);
    }

    private Transition endLife(LifeState state, LocalSimulation current, DeathCauseModel.Context context) {
        var death = death(current, "character", 0, state.stats().health(), context);
        int month = death.elapsedMonths();
        var history = new ArrayList<>(state.history());
        history.add(new LifeState.HistoryEvent("death-character", month / 12, "Жизнь завершилась",
                "Причина смерти: " + death.causeDetails().label() + ". В летописи сохраняются прожитые события и принятые решения."));
        var simulation = current.evolve(month, current.family(), current.skills(), current.flags(), current.lastEvents(), List.of(), null, death);
        return new Transition(new LifeState(month / 12, LifeState.Status.COMPLETE, state.stats(), null, history), simulation);
    }
    private ApiFailure conflict(String code, String message) { return new ApiFailure(HttpStatus.CONFLICT, code, message); }
    public record Transition(LifeState state, LocalSimulation simulation) { }
}
