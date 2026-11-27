package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.statistics.CountryCatalog;
import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;

/** Transaction-local working copy; never shared between requests. */
final class AdultWorld {
    LifeState state;
    LocalSimulation sim;
    LocalBudget budget;
    final FinanceView.Location location;
    final CountryCatalog countries;
    final double priceIndex;
    Map<String,Long> propertyPrices=Map.of();
    final int activatedMonth;
    int serial;
    final Traits traits;
    Education education;
    Career career;
    Partnership partnership;
    final Map<String, Npc> npcs;
    final Map<String, ContactState> contacts;
    final List<Disease> diseases;
    final List<Debt> debts;
    final List<Asset> assets;
    final List<Story> stories;
    final List<Memory> memories;
    Pending pending;
    String housing;
    final Set<String> flags;
    final SplittableRandom random;
    final BudgetState.Work management;

    AdultWorld(LifeState state, LocalSimulation sim, LocalBudget budget, AdultState saved,
            FinanceView.Location location, CountryCatalog countries, double priceIndex) {
        this.state = state; this.sim = sim; this.budget = budget; this.location = location;
        this.countries = countries; this.priceIndex = priceIndex;
        management=new BudgetState.Work(saved==null?null:saved.budgetManagement());
        random = new SplittableRandom(sim.seed() ^ ((long)sim.month() * 0x9E3779B97F4A7C15L) ^ (saved == null ? 0 : saved.serial()));
        activatedMonth = saved == null ? sim.month() : saved.activatedMonth();
        serial = saved == null ? 0 : saved.serial();
        traits = saved == null ? traits() : saved.traits();
        education = saved == null ? new Education("SECONDARY", null, null, null, "COMPLETED") : saved.education();
        career = saved == null ? new Career("UNEMPLOYED", null, null, null, null, 0, sim.month(), 0,
                0, 50, 50, 20, 0, List.of()) : saved.career();
        partnership = saved == null ? null : saved.partnership();
        npcs = new LinkedHashMap<>(saved == null ? Map.of() : saved.npcs());
        contacts = new LinkedHashMap<>(saved == null ? Map.of() : saved.contacts());
        diseases = new ArrayList<>(saved == null ? List.of() : saved.diseases());
        debts = new ArrayList<>(saved == null ? List.of() : saved.debts());
        assets = new ArrayList<>(saved == null ? List.of() : saved.assets());
        stories = new ArrayList<>(saved == null ? List.of() : saved.stories());
        memories = new ArrayList<>(saved == null ? List.of() : saved.memories());
        flags = new HashSet<>(saved == null ? Set.of() : saved.flags());
        if (sim.calibration() != null) flags.add("wages:" + sim.calibration().modelVersion());
        pending = saved == null ? null : saved.pending();
        housing = saved == null ? "FAMILY_HOME" : saved.housing();
        for (var p : sim.family()) if (!npcs.containsKey(p.id())) {
            List<String> parents = Set.of("brother", "sister").contains(p.role())
                    ? sim.family().stream().filter(r -> Set.of("father", "mother").contains(r.role())).map(LocalSimulation.Person::id).toList() : List.of();
            npcs.put(p.id(), new Npc(p.id(), traits(), "UNSPECIFIED", p.occupation(), 0, 70, "UNSPECIFIED", null,
                    location.countryCode(), parents, 0, Set.of("legacy-relative")));
        }
        synchronizeAssets();
        ContactSystem.initialize(this);
    }

    AdultState snapshot() { return new AdultState(VERSION, activatedMonth, serial, traits, education, career,
            partnership, npcs, diseases, debts, assets, stories, memories, pending, housing, flags, contacts,management.freeze()); }
    int month() { return sim.month(); }
    int age() { return month() / 12; }
    String id(String prefix) { return prefix + "-" + (++serial); }
    Traits traits() { return new Traits(random.nextInt(20, 91), random.nextInt(20, 91), random.nextInt(20, 91), random.nextInt(20, 91)); }
    long cost(int units) { return Math.round(units * 100_000d * priceIndex); }
    long salary(long gameAmount, String occupation) {
        return CalibratedWages.monthly(gameAmount, sim.calibration(), location.countryCode(), occupation);
    }
    boolean chance(double p) { return random.nextDouble() < Math.clamp(p, .01, .99); }
    LocalSimulation.Person person(String id) { return sim.family().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null); }
    LocalSimulation.Person actor(Story story) { return story.participants().isEmpty() ? null : person(story.participants().getFirst()); }
    List<LocalSimulation.Person> children() { return sim.family().stream().filter(p -> p.alive() && npcs.get(p.id()).parentIds().contains("character")).toList(); }
    boolean employed() { return Set.of("EMPLOYED", "PROBATION", "BUSINESS").contains(career.status()); }
    boolean coupled() { return partnership != null && !Set.of("SEPARATED", "DIVORCED", "WIDOWED").contains(partnership.status())
            && person(partnership.partnerId()) != null && person(partnership.partnerId()).alive(); }
    boolean owns(String item) { return budget.purchases().stream().anyMatch(p -> p.soldMonth()==null&&p.itemId().equals(item)); }
    long propertyPrice(String item) { return propertyPrices.getOrDefault(item,Long.MAX_VALUE); }
    void time(int month) {
        sim = sim.evolve(month, sim.family(), sim.skills(), sim.flags(), sim.lastEvents(), List.of(), null, sim.death());
        state = new LifeState(month / 12, state.status(), state.stats(), state.pendingDecision(), state.history());
    }
    void decision(LifeState.Decision value) { state = new LifeState(state.age(), state.status(), state.stats(), value, state.history()); }
    void stats(int health, int happiness, int intelligence, int aura) {
        var s = state.stats();
        state = new LifeState(state.age(), state.status(), new LifeState.Stats(clamp(s.health()+health), clamp(s.happiness()+happiness),
                clamp(s.intelligence()+intelligence), s.relationships(), s.appearance(), Math.clamp((s.aura()==null?0:s.aura())+aura,-100,100)), state.pendingDecision(), state.history());
    }
    void relationship(String id, int closeness, int trust) {
        var p = person(id); if (!ContactSystem.canInteract(this, id)) return;
        ContactSystem.interact(this, id);
        replacePerson(new LocalSimulation.Person(p.id(),p.role(),p.name(),p.birthMonth(),p.alive(),clamp(p.closeness()+closeness),
                clamp(p.trust()+trust),p.sex(),p.occupation(),p.diedMonth()));
        var s = state.stats();
        state = new LifeState(state.age(),state.status(),new LifeState.Stats(s.health(),s.happiness(),s.intelligence(),
                clamp(s.relationships()+closeness/3),s.appearance(),s.aura()),state.pendingDecision(),state.history());
    }
    void replacePerson(LocalSimulation.Person p) {
        var family = new ArrayList<>(sim.family()); family.removeIf(old -> old.id().equals(p.id())); family.add(p);
        sim = sim.evolve(month(),family,sim.skills(),sim.flags(),sim.lastEvents(),sim.scheduled(),sim.pendingTemplate(),sim.death());
    }
    String newPerson(String role, String sex, int birthMonth, List<String> parents) {
        String iso = countries.codes().stream().filter(c -> countries.get(c).iso2().equals(location.countryCode())).findFirst()
                .orElse(sim.birthFacts()==null?sim.request().countryIso3():sim.birthFacts().countryIso3());
        var c = countries.get(iso); var names = "FEMALE".equals(sex) ? c.femaleNames() : c.maleNames();
        String id = id("npc"); String name = names.get(random.nextInt(names.size()));
        if (sim.naming() != null) {
            var generated = com.life.life_backend.engine.names.PersonalNames.newPerson(countries.names(), sim.naming(), iso, name, sex,
                    parents, sim.seed(), month(), id);
            sim = sim.withNaming(sim.naming().with(id, generated));
            name = generated.display();
        }
        replacePerson(new LocalSimulation.Person(id,role,name,birthMonth,true,50,50,sex,null,null));
        npcs.put(id, new Npc(id,traits(),"NONE",null,0,random.nextInt(60,91),"SINGLE",null,location.countryCode(),parents,0,Set.of()));
        contacts.put(id, ContactState.start(month(), true));
        return id;
    }
    void remember(String kind, String personId, String text) { memories.add(new Memory(id("memory"),month(),kind,personId,text)); }
    void event(String title, String text, LifeState.Stats before, LocalSimulation old) {
        event(title,text,before,old,null);
    }
    void event(String title, String text, LifeState.Stats before, LocalSimulation old, com.life.life_backend.highlights.EventLink link) {
        var deltas = new LinkedHashMap<String,Integer>();
        for (var p : old.family()) { var now=person(p.id()); if(now!=null && now.closeness()!=p.closeness()) deltas.put(p.id(),now.closeness()-p.closeness()); }
        int aura=(state.stats().aura()==null?0:state.stats().aura())-(before.aura()==null?0:before.aura());
        var history = new ArrayList<>(state.history());
        history.add(new LifeState.HistoryEvent(id("adult-event"),Math.max(0,(month()-1)/12),title,text,new LifeState.Consequences(aura,deltas),link));
        state = new LifeState(state.age(),state.status(),state.stats(),state.pendingDecision(),history);
    }
    void event(String title,String text) { event(title,text,state.stats(),sim); }
    void synchronizeAssets() {
        for (var p : budget.purchases()) if (p.soldMonth()==null&&p.categoryId().equals("property") && assets.stream().noneMatch(a -> p.id().toString().equals(a.purchaseId())))
            assets.add(new Asset(id("asset"),"PROPERTY",p.title(),p.price(),p.price(),p.month(),100,p.id().toString()));
    }
    static int clamp(int n) { return Math.clamp(n,0,100); }
}
