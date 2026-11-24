package com.life.life_backend.engine.local;

import com.life.life_backend.engine.LifeState;
import java.util.*;

final class EffectApplier {
    private EffectApplier() { }
    static Result apply(LifeState.Stats stats, LocalSimulation simulation, EventCatalog.Effect effect, String role) {
        if (effect == null) return new Result(stats, simulation);
        var participant = simulation.participant(role);
        String target = participant == null ? null : participant.id();
        var family = simulation.family().stream().map(p -> p.id().equals(target)
                ? new LocalSimulation.Person(p.id(), p.role(), p.name(), p.birthMonth(), true,
                    score(p.closeness() + effect.closeness()), score(p.trust() + effect.closeness()),p.sex(),p.occupation()) : p).toList();
        var skills = new HashMap<>(simulation.skills());
        if (effect.skill() != null) skills.merge(effect.skill(), effect.skillDelta(), (a, b) -> score(a + b));
        var flags = new HashSet<>(simulation.flags());
        if (effect.addFlag() != null) flags.add(effect.addFlag());
        var queue = new ArrayList<>(simulation.scheduled());
        if (effect.followUp() != null) queue.add(new LocalSimulation.Scheduled(effect.followUp(), simulation.month() + effect.delayMonths()));
        var updated = simulation.evolve(simulation.month(),family,skills,flags,simulation.lastEvents(),queue,simulation.pendingTemplate(),simulation.death());
        // Apply the family delta, preserving relationship bonuses already granted by purchases.
        int before = (int) simulation.family().stream().filter(LocalSimulation.Person::alive)
                .mapToInt(LocalSimulation.Person::closeness).average().orElse(0);
        int after = (int) family.stream().filter(LocalSimulation.Person::alive)
                .mapToInt(LocalSimulation.Person::closeness).average().orElse(0);
        int bonds = score(stats.relationships() + after - before);
        return new Result(new LifeState.Stats(score(stats.health() + effect.health()), score(stats.happiness() + effect.happiness()),
                score(stats.intelligence() + effect.intelligence()), bonds, stats.appearance(),
                Math.clamp((stats.aura()==null?0:stats.aura())+effect.aura(),-100,100)), updated);
    }
    private static int score(int value) { return Math.clamp(value, 0, 100); }
    record Result(LifeState.Stats stats, LocalSimulation simulation) { }
}
