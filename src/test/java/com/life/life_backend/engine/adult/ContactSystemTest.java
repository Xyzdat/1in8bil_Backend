package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.life.life_backend.engine.adult.ContactState.Status.*;
import static com.life.life_backend.engine.adult.AdultState.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactSystemTest {
    private final ObjectMapper json = new ObjectMapper();

    private AdultWorld world() throws Exception {
        var people = List.of(new LocalSimulation.Person("mother", "mother", "Мама", -300, true, 70, 70),
                new LocalSimulation.Person("friend", "friend", "Друг", 0, true, 80, 70));
        var sim = new LocalSimulation(47, LocalSimulation.VERSION, LocalSimulation.STATISTICS,
                new LocalSimulation.BirthRequest("RUS", 2026), 360, people, Map.of(), Set.of(), Map.of(), List.of(), null);
        var state = new LifeState(30, LifeState.Status.ACTIVE, new LifeState.Stats(80, 70, 60, 70, 50, 0), null, List.of());
        return new AdultWorld(state, sim, new LocalBudget(360, 1_000_000, List.of(), List.of()), null,
                new FinanceView.Location("RU", "Россия", null), new CountryCatalog(json), 1);
    }

    private StoryProgress progress(String template, String actor) {
        return new StoryProgress(new Story("story-test", template, "REVIEW", "ACTIVE", 360, 362, null,
                List.of(actor), Map.of(), List.of("CHOICE")));
    }

    @Test void elapsedTimeChangesContactWithoutChangingAffectionAndHouseholdStaysInTouch() throws Exception {
        var w = world();
        assertThat(w.contacts.get("friend").lastContactMonth()).isNull();
        w.time(364); ContactSystem.step(w); assertThat(w.contacts.get("friend").status()).isEqualTo(IN_TOUCH);
        w.time(366); ContactSystem.step(w); assertThat(w.contacts.get("friend").status()).isEqualTo(RARE_CONTACT);
        w.time(384); ContactSystem.step(w); assertThat(w.contacts.get("friend").status()).isEqualTo(NO_CONTACT);
        assertThat(w.person("friend").closeness()).isEqualTo(80);
        assertThat(w.contacts.get("mother").status()).isEqualTo(IN_TOUCH);
        int events = w.state.history().size();
        ContactSystem.step(w); assertThat(w.state.history()).hasSize(events);
    }

    @Test void aConversationRenewsRareContactButNeverSilentlyReopensBrokenContact() throws Exception {
        var w = world(); w.time(366); ContactSystem.step(w);
        new FamilySystem().act("friend_talk", w, progress("friendship", "friend"));
        assertThat(w.contacts.get("friend").status()).isEqualTo(IN_TOUCH);
        assertThat(w.contacts.get("friend").lastContactMonth()).isEqualTo(366);
        var p = progress("restore-contact", "friend"); new ContactSystem().act("end_contact", w, p);
        var before = w.contacts.get("friend"); int closeness = w.person("friend").closeness();
        w.relationship("friend", 10, 10);
        assertThat(w.contacts.get("friend")).isEqualTo(before); assertThat(w.person("friend").closeness()).isEqualTo(closeness);
        assertThat(AdultConditions.participant("friend", w)).isNull();
        new AdultDailyEvents().next(w); assertThat(w.contacts.get("friend").status()).isEqualTo(NO_CONTACT);
    }

    @Test void separationAndStoppingContactAreDifferentDecisions() throws Exception {
        var w = world(); String id = w.newPerson("partner", "FEMALE", 12, List.of());
        w.partnership = new Partnership(id, "MARRIED", 300, true, 70, 10, List.of());
        FamilySystem.separate(w, id);
        assertThat(w.partnership.status()).isEqualTo("DIVORCED");
        assertThat(w.contacts.get(id).status()).isEqualTo(IN_TOUCH);
        new ContactSystem().act("end_contact", w, progress("restore-contact", id));
        assertThat(w.contacts.get(id).status()).isEqualTo(NO_CONTACT);
        assertThat(w.contacts.get(id).reason()).isEqualTo(ContactState.Reason.PLAYER_CHOICE);
        assertThat(w.person(id)).isNotNull(); assertThat(w.npcs).containsKey(id);
    }

    @Test void reconnectionWaitsForResponseMayFailAndDoesNotRestartRomance() throws Exception {
        boolean sawSuccess = false, sawFailure = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            var w = world(); w.random.nextLong(); // Same repeatable generator, vary the draw deliberately.
            for (int draw = 0; draw < attempt; draw++) w.random.nextLong();
            var p = progress("restore-contact", "friend"); var system = new ContactSystem();
            system.act("end_contact", w, p); var old = w.contacts.get("friend");
            system.act("reach_out", w, p); assertThat(w.contacts.get("friend")).isEqualTo(old);
            w.time(362); var answer = system.act("contact_response", w, p);
            if (answer.proceed()) {
                sawSuccess = true; assertThat(w.contacts.get("friend").status()).isEqualTo(IN_TOUCH);
                assertThat(w.contacts.get("friend").lastContactMonth()).isEqualTo(362);
            } else {
                sawFailure = true; assertThat(w.contacts.get("friend")).isEqualTo(old);
            }
            assertThat(w.partnership).isNull();
        }
        assertThat(sawSuccess).isTrue(); assertThat(sawFailure).isTrue();
    }

    @Test void oldJsonLoadsWithoutInventingLastConversationAndPurchasesPreserveContacts() throws Exception {
        var original = world(); ObjectNode legacy = (ObjectNode) json.valueToTree(original.snapshot()); legacy.remove("contacts");
        var saved = json.treeToValue(legacy, AdultState.class); assertThat(saved.contacts()).isEmpty();
        var restored = new AdultWorld(original.state, original.sim, original.budget, saved, original.location, original.countries, 1);
        assertThat(restored.contacts.get("mother").trackedSinceMonth()).isEqualTo(360);
        assertThat(restored.contacts.get("mother").lastContactMonth()).isNull();
        var p = progress("restore-contact", "friend"); new ContactSystem().act("end_contact", restored, p);
        var copy = json.readValue(json.writeValueAsString(restored.snapshot()), AdultState.class);
        assertThat(copy).isEqualTo(restored.snapshot());
        var purchase = new LocalBudget.Purchase(UUID.randomUUID(), 360, "property", "bicycle", "Велосипед", null, 600000, 0, null, null, null);
        assertThat(copy.withPurchase(purchase).contacts()).isEqualTo(copy.contacts());
    }

    @Test void deathIsNotEstrangementAndContactDoesNotDisappearFromSavedHistory() throws Exception {
        var w = world(); var before = w.contacts.get("friend"); var p = w.person("friend");
        w.replacePerson(new LocalSimulation.Person(p.id(), p.role(), p.name(), p.birthMonth(), false, p.closeness(), p.trust(), p.sex(), null, 361));
        w.time(400); ContactSystem.step(w);
        assertThat(w.contacts.get("friend")).isEqualTo(before);
        var visible = w.sim.view(w.snapshot()).family().stream().filter(person -> person.id().equals("friend")).findFirst().orElseThrow();
        assertThat(visible.alive()).isFalse(); assertThat(visible.contact()).isNull();
        assertThat(new ContactSystem().act("contact_response", w, progress("restore-contact", "friend")).proceed()).isFalse();
    }

    @Test void lostContactCancelsAnAlreadyScheduledMeeting() throws Exception {
        var w = world(); var p = progress("friendship", "friend"); w.stories.add(p.story);
        new ContactSystem().act("end_contact", w, p);
        var refs = mock(CountryReference.class); when(refs.all()).thenReturn(List.of());
        var spending = mock(SpendingService.class);
        when(spending.shop(any(), eq("property"))).thenReturn(new FinanceView.Shop(1, 30, FinanceView.Currency.RUB, null, "", "", "", null, w.state.stats(), List.of(), List.of()));
        var engine = new AdultLifeEngine(new AdultStoryCatalog(json), new LocalSimulationEngine(new EventCatalog(json), mock(MortalityModel.class)), refs, w.countries, spending);
        var result = engine.step(w.state, w.sim, w.snapshot(), w.budget, w.location);
        assertThat(result.adult().stories().stream().filter(s -> s.id().equals("story-test")).findFirst().orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(result.adult().contacts().get("friend").status()).isEqualTo(NO_CONTACT);
        assertThat(result.state().history()).anyMatch(e -> e.text().contains("после прекращения общения"));
    }
}
