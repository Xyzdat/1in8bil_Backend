package com.life.life_backend.engine.adult;

import java.util.Set;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

/** Activities after retirement leave pension and employment unchanged. */
final class LaterLifeSystem {
    static final Set<String> ACTIONS = Set.of("mentor", "volunteer", "family_time", "quiet_retirement", "later_life_review");
    StoryProgress.Result act(String action, AdultWorld w, StoryProgress p) {
        p.put("activity", action.equals("later_life_review") ? p.get("activity") : action);
        return switch (action) {
            case "mentor" -> { w.stats(0, 3, 2, 2); yield next("Ты делишься накопленным опытом на встречах с начинающими. Это добровольное занятие, пенсия сохраняется."); }
            case "volunteer" -> { w.stats(-1, 4, 0, 4); yield next("Ты присоединяешься к местным волонтёрам. Помощь другим даёт ощущение нужности, но требует сил."); }
            case "family_time" -> {
                var relative = w.sim.family().stream().filter(person -> person.alive() && !person.role().equals("friend") && ContactSystem.canInteract(w, person.id()))
                        .sorted(java.util.Comparator.comparingInt(com.life.life_backend.engine.local.LocalSimulation.Person::closeness).reversed()
                                .thenComparing(com.life.life_backend.engine.local.LocalSimulation.Person::id)).findFirst();
                if (relative.isEmpty()) yield end("Сейчас рядом нет близких, с которыми поддерживается связь. Ты оставляешь время для отдыха и заботы о себе.");
                p.put("relative", relative.get().id()); w.relationship(relative.get().id(), 5, 3); w.stats(0, 4, 0, 2);
                yield next("Ты уделяешь больше времени близкому человеку: " + relative.get().name() + ". Совместные дела укрепляют вашу связь.");
            }
            case "quiet_retirement" -> { w.stats(3, 2, 0, 0); yield next("Ты выбираешь спокойный ритм: прогулки, отдых и заботу о здоровье."); }
            case "later_life_review" -> {
                String activity = p.get("activity");
                if ("family_time".equals(activity) && p.get("relative") != null && ContactSystem.canInteract(w, p.get("relative"))) {
                    w.relationship(p.get("relative"), 3, 2); w.stats(0, 2, 0, 1);
                    yield end("Регулярные встречи стали семейной привычкой. Близость выросла благодаря времени, проведённому вместе.");
                }
                w.stats("quiet_retirement".equals(activity) ? 2 : 0, 2, "mentor".equals(activity) ? 1 : 0, "volunteer".equals(activity) ? 1 : 0);
                yield end("Ты оцениваешь выбранный ритм жизни и сохраняешь занятия, на которые хватает сил. Работа не возобновляется, пенсионные выплаты продолжаются.");
            }
            default -> throw new IllegalArgumentException(action);
        };
    }
}
