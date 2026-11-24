package com.life.life_backend.engine.local;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import java.util.*;

@Component
public class EventCatalog {
    private final List<Definition> definitions;
    public EventCatalog(ObjectMapper json) throws Exception {
        try (var stream = new ClassPathResource("events/childhood-2.json").getInputStream()) {
            // Optional numeric effects in the authored catalog default to zero, independently of HTTP validation.
            Definition[] loaded = json.readerFor(Definition[].class)
                    .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(stream);
            definitions = List.of(loaded);
        }
        Set<String> ids = new HashSet<>();
        for (var d : definitions) {
            if (!ids.add(d.id()) || d.minMonth() < 0 || d.maxMonth() < d.minMonth() || d.cooldownMonths() < 0
                    || d.weight() <= 0 || d.text() == null || (d.options() != null && d.minMonth() < 36))
                throw new IllegalStateException("Invalid event definition: " + d.id());
            if (d.options() != null && (d.options().size() < 2 || d.options().stream().map(Choice::id).distinct().count() != d.options().size()))
                throw new IllegalStateException("Invalid choices: " + d.id());
        }
        for (var d : definitions) {
            validateEffect(d.effect(), ids);
            if (d.options() != null) d.options().forEach(c -> validateEffect(c.effect(), ids));
        }
    }
    private void validateEffect(Effect effect, Set<String> ids) {
        if (effect == null) return;
        if (effect.followUp() != null && (!ids.contains(effect.followUp()) || effect.delayMonths() < 2))
            throw new IllegalStateException("Invalid follow-up");
    }
    public List<Definition> all() { return definitions; }
    public Definition get(String id) { return definitions.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(); }
    public record Definition(String id, int minMonth, int maxMonth, int cooldownMonths, boolean once,
            int weight, String requiredFlag, String excludedFlag, String participantRole, boolean followUpOnly,
            String title, String text, Effect effect, List<Choice> options,
            String requiredSkill, int minSkill, int minCloseness, List<String> variants) { }
    public record Choice(String id, String label, String text, Effect effect) { }
    public record Effect(int health, int happiness, int intelligence, int closeness, String skill,
            int skillDelta, String addFlag, String followUp, int delayMonths,int aura) {
        public Effect(int health,int happiness,int intelligence,int closeness,String skill,int skillDelta,String addFlag,String followUp,int delayMonths){
            this(health,happiness,intelligence,closeness,skill,skillDelta,addFlag,followUp,delayMonths,0);
        }
    }
}
