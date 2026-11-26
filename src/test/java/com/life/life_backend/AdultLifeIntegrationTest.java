package com.life.life_backend;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.adult.*;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LIFE_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.datasource.url=${LIFE_TEST_DB_URL}","spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}","spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class AdultLifeIntegrationTest {
    @Autowired StatisticsImporter importer;
    @Autowired CharacterGenerator generator;
    @Autowired LocalSimulationEngine childhood;
    @Autowired AdultLifeEngine engine;
    @Autowired CountryCatalog countries;
    @Autowired ObjectMapper json;
    @Autowired AdultStoryCatalog storyCatalog;

    @Test void thousandFullLivesPreserveMoneyGenealogyDecisionsAndDiversity() throws Exception {
        importer.importBundled();var codes=new ArrayList<>(countries.codes());
        var templates=new TreeMap<String,Integer>();var completed=new TreeMap<String,Integer>();var reviewed=new TreeMap<String,Integer>();var careers=new TreeMap<String,Integer>();
        var sequences=new HashSet<Integer>();var ages=new ArrayList<Integer>();
        var observedContactStates=EnumSet.noneOf(ContactState.Status.class);
        var deathCauses = new TreeMap<String,Integer>();
        var highlightTracker=new com.life.life_backend.highlights.HighlightTracker();
        int highlightLives=0,highlightSlides=0;
        long decisions=0,children=0,grandchildren=0,diseases=0,unaffordable=0,adultLives=0,single=0,layoffs=0,storyMonths=0,storyEnds=0,repeatTitles=0,eventCount=0;
        for(int i=0;i<1000;i++) {
            long seed=5_000_000L+i;var born=generator.generate(seed,new LocalSimulation.BirthRequest(codes.get(i%codes.size()),2026));
            var state=born.state();var sim=born.simulation();AdultState adult=null;var budget=LocalBudget.atMonth(null,0);
            var highlights=com.life.life_backend.highlights.LifeHighlights.start(0,state.history().size());
            var location=new FinanceView.Location(born.character().birthplace().countryCode(),born.character().birthplace().country(),null);
            int steps=0;
            while(state.status()==LifeState.Status.ACTIVE&&steps++<1200) {
                var beforeHighlights=i<36?new com.life.life_backend.highlights.HighlightFrame(state,sim,adult,budget):null;
                if(sim.month()<216||(state.pendingDecision()!=null&&adult==null)) {
                    var t=state.pendingDecision()==null?childhood.step(state,sim):childhood.answer(state,sim,state.pendingDecision().id(),state.pendingDecision().options().getFirst().id());
                    state=t.state();sim=t.simulation();budget=LocalBudget.atMonth(budget,sim.month());
                    if(i<36)highlights=highlightTracker.update(highlights,beforeHighlights,new com.life.life_backend.highlights.HighlightFrame(state,sim,adult,budget));
                    continue;
                }
                AdultLifeEngine.Transition t;
                if(state.pendingDecision()==null)t=engine.step(state,sim,adult,budget,location);
                else {
                    decisions++;var decision=state.pendingDecision();var options=decision.options();
                    // Distinct stable policies: proactive, cautious, social, independent, and randomized.
                    int pick=(i%5==1?options.size()-1:i%5==2?Math.min(1,options.size()-1):i%5==4?Math.floorMod(Objects.hash(seed,sim.month()),options.size()):0);
                    String pendingId=adultPending(adult);
                    if(i%5==3&&Set.of("romance","marriage","family-planning","adoption").contains(adult.stories().stream().filter(s->s.id().equals(pendingId)).findFirst().orElseThrow().template()))pick=options.size()-1;
                    try {t=engine.answer(state,sim,adult,budget,location,decision.id(),options.get(pick).id());}
                    catch(com.life.life_backend.web.ApiFailure failure) {
                        if(!failure.getMessage().contains("средств"))throw failure;
                        unaffordable++;t=engine.answer(state,sim,adult,budget,location,decision.id(),options.getLast().id());
                    }
                    assertThat(t.simulation().month()).isEqualTo(sim.month());assertThat(t.budget().month()).isEqualTo(budget.month());
                }
                state=t.state();sim=t.simulation();adult=t.adult();budget=t.budget();
                if(i<36)highlights=highlightTracker.update(highlights,beforeHighlights,new com.life.life_backend.highlights.HighlightFrame(state,sim,adult,budget));
                adult.contacts().values().forEach(c -> observedContactStates.add(c.status()));
                assertThat(budget.month()).isEqualTo(sim.month());
                if(sim.month()%120==0&&state.pendingDecision()==null&&state.status()==LifeState.Status.ACTIVE) {
                    var restored=json.readValue(json.writeValueAsString(t),AdultLifeEngine.Transition.class);
                    assertThat(restored).isEqualTo(t);
                    var a=engine.step(state,sim,adult,budget,location);var b=engine.step(restored.state(),restored.simulation(),restored.adult(),restored.budget(),location);
                    assertThat(a).isEqualTo(b);
                }
            }
            assertThat(state.status()).as("life %s",i).isEqualTo(LifeState.Status.COMPLETE);
            var offersByYear=state.history().stream().filter(e->e.link()!=null&&e.link().phase().equals("OFFER"))
                    .collect(java.util.stream.Collectors.groupingBy(LifeState.HistoryEvent::age,java.util.stream.Collectors.counting()));
            assertThat(offersByYear.values()).allMatch(count->count<=2);
            if(i<36) {
                assertThat(highlights.status()).isEqualTo("FINAL");
                assertThat(highlights.partialHistory()).isFalse();
                assertThat(highlights.view().slides()).hasSizeLessThanOrEqualTo(12);
                assertThat(highlights.view().slides()).extracting(com.life.life_backend.highlights.LifeHighlights.Slide::month).isSorted();
                assertThat(highlights.view().decisionCount()).isEqualTo((int)state.history().stream().filter(e->e.link()!=null&&e.link().phase().equals("DECISION")).count());
                var ids=state.history().stream().map(LifeState.HistoryEvent::id).collect(java.util.stream.Collectors.toSet());
                for(var candidate:highlights.candidates()) {
                    assertThat(candidate.decisions()).allMatch(d->ids.contains(d.historyEventId()));
                    assertThat(candidate.evidence()).allMatch(e->e.historyEventId()==null||ids.contains(e.historyEventId()));
                }
                assertThat(json.readValue(json.writeValueAsString(highlights),com.life.life_backend.highlights.LifeHighlights.class)).isEqualTo(highlights);
                highlightLives++;highlightSlides+=highlights.view().slides().size();
            }
            var death = sim.death();
            assertThat(death.causeDetails()).isNotNull();
            assertThat(state.history().getLast().text()).contains(death.causeDetails().label());
            deathCauses.merge(death.causeDetails().code().name(), 1, Integer::sum);
            assertThat(json.readValue(json.writeValueAsString(sim), LocalSimulation.class)).isEqualTo(sim);
            for (var person : sim.family()) if (!person.alive()) {
                assertThat(person.death()).isNotNull();
                assertThat(person.death().causeDetails()).isNotNull();
                assertThat(person.death().age()).isEqualTo((person.diedMonth() - person.birthMonth()) / 12);
            }
            if (death.causeDetails().conditionId() != null) {
                assertThat(adult).isNotNull();
                assertThat(adult.diseases()).anyMatch(d -> d.id().equals(death.causeDetails().conditionId()) && d.endedMonth() == null);
            }
            assertThat(state.pendingDecision()).isNull();assertThat(state.history().stream().filter(h->h.id().equals("death-character")).count()).isEqualTo(1);
            assertThat(state.history().stream().map(LifeState.HistoryEvent::id)).doesNotHaveDuplicates();
            assertThat(budget.entries().stream().map(LocalBudget.Entry::id)).doesNotHaveDuplicates();
            assertThat(budget.balance()).isEqualTo(budget.entries().stream().mapToLong(e->e.income()-e.expense()).sum());
            assertThat(sim.family().stream().map(LocalSimulation.Person::id)).doesNotHaveDuplicates();
            ages.add(state.age());sequences.add(state.history().stream().map(LifeState.HistoryEvent::title).toList().hashCode());
            var seen=new HashSet<String>();for(var e:state.history()) {eventCount++;if(!seen.add(e.age()+":"+e.title()))repeatTitles++;}
            if(adult!=null) {
                adultLives++;diseases+=adult.diseases().size();
                if(adult.partnership()==null)single++;
                assertThat(adult.pending()).isNull();assertThat(adult.stories()).noneMatch(s->s.status().equals("ACTIVE"));
                assertThat(adult.career().history()).noneMatch(e->e.endedMonth()==null);
                assertThat(adult.career().history()).allMatch(e->e.beganMonth()<65*12);
                for(var job:adult.career().history()) {careers.merge(job.occupation(),1,Integer::sum);if("Сокращение".equals(job.endReason()))layoffs++;}
                for(var s:adult.stories()) {templates.merge(s.template(),1,Integer::sum);if(s.visitedStages().contains("REVIEW"))reviewed.merge(s.template(),1,Integer::sum);if(s.status().equals("COMPLETED"))completed.merge(s.template(),1,Integer::sum);if(s.endedMonth()!=null){storyMonths+=s.endedMonth()-s.beganMonth();storyEnds++;}}
                var byId=new HashMap<String,LocalSimulation.Person>();sim.family().forEach(p->byId.put(p.id(),p));
                for(var n:adult.npcs().values()) {
                    var person=byId.get(n.id());assertThat(person).isNotNull();
                    if(n.parentIds().contains("character"))children++;
                    if(person.role().equals("grandchild")||person.role().equals("great-grandchild"))grandchildren++;
                    for(String parent:n.parentIds()) {
                        if(parent.equals("character")){assertThat(person.birthMonth()).isGreaterThanOrEqualTo(216);continue;}
                        assertThat(byId).containsKey(parent);assertThat(person.birthMonth()-byId.get(parent).birthMonth()).isGreaterThanOrEqualTo(216);
                    }
                }
            }
            if(i%100==99)System.out.println("Adult full-life audit: "+(i+1)+"/1000");
        }
        var metrics=new LinkedHashMap<String,Object>();metrics.put("model","adult-1");metrics.put("lives",1000);metrics.put("countries",codes);metrics.put("adultLives",adultLives);
        metrics.put("highlightLives",highlightLives);metrics.put("highlightSlides",highlightSlides);
        metrics.put("deathCauses",deathCauses);metrics.put("distinctTitleSequences",sequences.size());metrics.put("choices",decisions);metrics.put("children",children);metrics.put("descendants",grandchildren);metrics.put("conditions",diseases);
        metrics.put("neverPartnered",single);metrics.put("layoffs",layoffs);metrics.put("unaffordableChoicesRejected",unaffordable);metrics.put("minDeathAge",Collections.min(ages));metrics.put("maxDeathAge",Collections.max(ages));
        metrics.put("meanDeathAge",ages.stream().mapToInt(Integer::intValue).average().orElseThrow());metrics.put("meanStoryDurationMonths",storyMonths/(double)Math.max(1,storyEnds));
        metrics.put("sameYearRepeatedTitles",repeatTitles);metrics.put("events",eventCount);metrics.put("storyStarts",templates);metrics.put("storyCompletions",completed);metrics.put("reviewedStories",reviewed);metrics.put("occupations",careers);
        Files.writeString(Path.of("target/adult-simulation-metrics.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
        var expectedStories=storyCatalog.all().stream().map(AdultStoryCatalog.Definition::id).toList();
        assertThat(templates.keySet()).containsExactlyInAnyOrderElementsOf(expectedStories);
        assertThat(completed.keySet()).containsExactlyInAnyOrderElementsOf(expectedStories);
        assertThat(reviewed.keySet()).containsExactlyInAnyOrderElementsOf(expectedStories);
        assertThat(observedContactStates).containsExactlyInAnyOrder(ContactState.Status.values());
        assertThat(sequences.size()).isGreaterThan(950);assertThat(careers.size()).isGreaterThanOrEqualTo(6);
        assertThat(children).isGreaterThan(100);assertThat(grandchildren).isGreaterThan(10);assertThat(layoffs).isGreaterThan(0);assertThat(single).isGreaterThan(0);assertThat(decisions).isGreaterThan(5000);
    }
    private static String adultPending(AdultState a){return a.pending().storyId();}
}
