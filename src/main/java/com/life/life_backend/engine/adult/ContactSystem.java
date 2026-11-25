package com.life.life_backend.engine.adult;

import java.util.Set;
import com.life.life_backend.engine.local.LocalSimulation;
import static com.life.life_backend.engine.adult.ContactState.Status.*;
import static com.life.life_backend.engine.adult.ContactState.Reason.*;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

/** Gameplay intervals, not demographic estimates. Time passes only through /step. */
final class ContactSystem {
    static final int RARE_AFTER_MONTHS = 6;
    static final int LOST_AFTER_MONTHS = 24;
    static final Set<String> ACTIONS = Set.of("reach_out", "contact_response", "contact_followup", "end_contact");

    static void initialize(AdultWorld w) {
        for (var person : w.sim.family()) if (person.alive())
            w.contacts.putIfAbsent(person.id(), ContactState.start(w.month(), false));
    }

    static boolean canInteract(AdultWorld w, String id) {
        var person = w.person(id);
        var contact = w.contacts.get(id);
        return person != null && person.alive() && (contact == null || contact.status() != NO_CONTACT);
    }

    static void interact(AdultWorld w, String id) {
        if (canInteract(w, id))
            w.contacts.put(id, w.contacts.getOrDefault(id, ContactState.start(w.month(), false)).touched(w.month(), INTERACTION));
    }

    static void step(AdultWorld w) {
        initialize(w);
        for (var person : w.sim.family()) {
            if (!person.alive()) continue; // Death is not estrangement. Freeze the saved contact record.
            var current = w.contacts.get(person.id());
            if (current.status() == NO_CONTACT) continue; // Only an explicit, successful reconnection can reopen it.
            if (ongoingHousehold(w, person)) {
                w.contacts.put(person.id(), current.touched(w.month(), HOUSEHOLD));
            } else {
                var next = current.withoutContact(w.month());
                w.contacts.put(person.id(), next);
                if (next.status() == NO_CONTACT && current.status() != NO_CONTACT)
                    w.event("Связь прервалась", "Вы с " + person.name() + " давно не общались. Общая история сохранена, но для возобновления связи потребуется отдельная попытка.");
            }
        }
    }

    private static boolean ongoingHousehold(AdultWorld w, LocalSimulation.Person p) {
        if (w.coupled() && w.partnership.partnerId().equals(p.id())) return true;
        if (w.housing.equals("FAMILY_HOME") && Set.of("mother", "father", "brother", "sister").contains(p.role())) return true;
        var npc = w.npcs.get(p.id());
        return npc != null && npc.parentIds().contains("character") && w.month() - p.birthMonth() < 216;
    }

    StoryProgress.Result act(String action, AdultWorld w, StoryProgress progress) {
        String id = progress.actor();
        var person = id == null ? null : w.person(id);
        if (person == null || !person.alive()) return end("Общение с этим человеком больше невозможно.");
        var contact = w.contacts.getOrDefault(id, ContactState.start(w.month(), false));
        return switch (action) {
            case "end_contact" -> {
                if (FamilySystem.isCurrent(w, id)) FamilySystem.separate(w, id);
                contact = w.contacts.getOrDefault(id, contact);
                w.contacts.put(id, new ContactState(NO_CONTACT, contact.trackedSinceMonth(), contact.lastContactMonth(), w.month(), PLAYER_CHOICE));
                w.remember("contact-ended", id, "Ты решил прекратить общение");
                yield end("Ты решаешь прекратить общение с " + person.name() + ". Родственные связи и прошлые события сохраняются, но совместные встречи больше не планируются.");
            }
            case "reach_out" -> next("Ты отправляешь сообщение человеку по имени " + person.name() + ". Ответ ещё не получен; прежний статус связи пока сохраняется.");
            case "contact_response" -> {
                if (!w.chance(.25 + person.closeness() / 250d + person.trust() / 300d))
                    yield end(person.name() + " не готов возобновить общение. Статус связи остаётся прежним.");
                w.contacts.put(id, contact.touched(w.month(), RECONNECTED));
                w.relationship(id, 3, 2);
                // relationship() records the conversation; keep the reason explaining this transition.
                w.contacts.put(id, w.contacts.get(id).touched(w.month(), RECONNECTED));
                w.remember("contact-restored", id, "Вы возобновили общение");
                yield next(person.name() + " отвечает, и вы разговариваете. Общение возобновляется; это не возвращает автоматически прежние романтические отношения.");
            }
            case "contact_followup" -> {
                if (!canInteract(w, id)) yield end("Связь снова прервана. Запланированный разговор не состоялся.");
                w.relationship(id, 2, 2);
                yield end("Вы с " + person.name() + " снова находите время для разговора и постепенно выстраиваете общение.");
            }
            default -> throw new IllegalArgumentException(action);
        };
    }
}
