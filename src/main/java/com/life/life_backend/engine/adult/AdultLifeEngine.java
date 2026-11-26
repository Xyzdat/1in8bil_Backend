package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;

@Component
public class AdultLifeEngine {
    private final AdultStoryCatalog catalog;
    private final LocalSimulationEngine mortality;
    private final CountryReference references;
    private final CountryCatalog countries;
    private final AdultEconomy economy;
    private final SpendingService spending;
    private final CareerSystem career=new CareerSystem();
    private final FamilySystem family=new FamilySystem();
    private final HealthSystem health=new HealthSystem();
    private final NpcLifeSystem npcs=new NpcLifeSystem();
    private final AdultDailyEvents daily=new AdultDailyEvents();
    private final ContactSystem contacts=new ContactSystem();
    private final LaterLifeSystem laterLife=new LaterLifeSystem();
    public AdultLifeEngine(AdultStoryCatalog catalog,LocalSimulationEngine mortality,CountryReference references,CountryCatalog countries,SpendingService spending) {
        this.catalog=catalog;this.mortality=mortality;this.references=references;this.countries=countries;this.spending=spending;economy=new AdultEconomy(spending);
    }
    public Transition step(LifeState state,LocalSimulation sim,AdultState adult,LocalBudget budget,FinanceView.Location location) {
        var w=world(state,sim,adult,budget,location);
        if (state.stats().health() <= 0) return transition(w);
        RetirementPolicy.apply(w,catalog);
        if(w.state.pendingDecision()!=null)throw conflict("DECISION_REQUIRED","Answer the pending decision first.");
        Map<String,Integer> healthById=new HashMap<>();w.npcs.forEach((id,n) -> healthById.put(id,n.health()));
        var checked=mortality.checkMortality(w.state,w.sim,healthById,causeContext(w));
        int target=checked.state().status()==LifeState.Status.COMPLETE?checked.simulation().month():sim.month()+2;
        // Earn/spend only for the elapsed interval; an event at its end affects future payments.
        economy.settle(w,target);
        w.state=checked.state();w.sim=checked.simulation();w.time(target);
        if(w.state.status()==LifeState.Status.COMPLETE) {
            return transition(w);
        }
        npcs.step(w,sim.month());HealthSystem.interval(w);
        if (w.state.stats().health() <= 0) return transition(w);
        RetirementPolicy.apply(w,catalog);
        ContactSystem.step(w);
        cancelInvalid(w);
        deferQuestions(w);
        var due=w.stories.stream().filter(s -> s.status().equals("ACTIVE")&&s.dueMonth()<=w.month())
                .sorted(Comparator.comparingInt(Story::dueMonth).thenComparing(Story::id)).findFirst();
        if(due.isPresent())review(w,due.get());
        else {
            var eligible=catalog.all().stream().filter(d -> eligible(d,w)).toList();
            if(!eligible.isEmpty()&&w.stories.stream().filter(s -> s.status().equals("ACTIVE")).count()<4
                    &&DecisionPacing.allowed(w.state,w.sim,w.month())&&w.chance(AdultDecisionPacing.probability(w))) {
                int draw=w.random.nextInt(eligible.stream().mapToInt(d -> weight(d,w)).sum());
                for(var d:eligible) { draw-=weight(d,w);if(draw<0){start(w,d);break;} }
            } else daily.next(w);
        }
        return transition(w);
    }
    public Transition answer(LifeState state,LocalSimulation sim,AdultState adult,LocalBudget budget,FinanceView.Location location,String decisionId,String optionId) {
        var w=world(state,sim,adult,budget,location);
        if (state.stats().health() <= 0) return transition(w);
        if(w.pending==null||state.pendingDecision()==null||!state.pendingDecision().id().equals(decisionId))throw conflict("DECISION_CHANGED","This decision is not pending.");
        var story=w.stories.stream().filter(s -> s.id().equals(w.pending.storyId())&&s.status().equals("ACTIVE")).findFirst().orElseThrow();
        var d=catalog.get(story.template());
        AdultStoryCatalog.Choice choice=choices(d,story).stream().filter(c -> c.id().equals(optionId)).findFirst()
                .orElseThrow(() -> new ApiFailure(HttpStatus.BAD_REQUEST,"INVALID_OPTION","Unknown option."));
        if(!w.pending.options().contains(choice.id()))throw conflict("DECISION_CHANGED","This option was not offered.");
        RetirementPolicy.apply(w,catalog);
        // An old save may contain an obsolete employment offer after pension age. Close it without charging/answering it.
        if(w.pending==null) return transition(w);
        long cost=Long.parseLong(story.variables().getOrDefault("cost:"+choice.id(),"0"));
        if(w.budget.available()<cost)throw conflict("SPENDING_INSUFFICIENT_FUNDS","Недостаточно средств для этого решения.");
        validatePurchaseChoice(w,choice.action());
        var before=w.state.stats();var old=w.sim;var p=new StoryProgress(story);
        if(cost>0)w.budget=w.budget.post(story.id()+"-choice",choice.label(),0,cost,false);
        var result=act(choice.action(),w,p);
        if(choice.action().equals("retrain")) {p.put("reviewAction","qualify");p.put("reviewDelay",6);p.put("noLater",true);}
        if(choice.action().equals("pass")&&d.id().equals("inheritance")) {
            var n=w.npcs.get(p.actor()); w.npcs.put(n.id(),new Npc(n.id(),n.traits(),n.education(),n.occupation(),n.monthlyIncome(),n.health(),n.maritalStatus(),n.partnerId(),n.countryIso2(),n.parentIds(),0,n.milestones()));
        }
        w.pending=null;w.decision(null);
        var visited=new ArrayList<>(story.visitedStages());visited.add("ANSWER:"+choice.id());
        int delay=Integer.parseInt(p.variables.getOrDefault("reviewDelay",String.valueOf(d.reviewMonths())));
        var next=new Story(story.id(),story.template(),"REVIEW","ACTIVE",story.beganMonth(),w.month()+delay,null,story.participants(),p.variables,visited);
        if(!result.proceed()||story.stage().equals("COHABIT_CHOICE"))next=finish(next,"COMPLETED",w.month(),p.variables);
        replace(w,next);w.event("Твоё решение: "+state.pendingDecision().title(),result.text(),before,old,
                link(w,next,d,"DECISION",choice.action(),state.pendingDecision(),choice));
        cancelInvalid(w);
        RetirementPolicy.apply(w,catalog);
        return transition(w);
    }
    private AdultWorld world(LifeState state,LocalSimulation sim,AdultState adult,LocalBudget budget,FinanceView.Location location) {
        if(state.status()==LifeState.Status.COMPLETE)throw conflict("SESSION_COMPLETE","This life has ended.");
        if(sim.month()<216)throw conflict("ADULT_AGE_REQUIRED","Adult simulation begins at eighteen.");
        if(adult!=null&&!AdultState.VERSION.equals(adult.modelVersion()))throw conflict("ENGINE_VERSION_UNAVAILABLE","Unsupported adult engine version.");
        var profile=references.all().stream().filter(c -> c.iso2().equals(location.countryCode())).findFirst();
        // A documented game purchasing-power index, NOT USD→RUB conversion or a wage statistic.
        double index=profile.map(c -> Math.clamp(Math.sqrt(c.gdpPerCapitaUsd()/15000d),.6,1.8)).orElse(1d);
        var world=new AdultWorld(state,sim,budget,adult,location,countries,index);
        var prices=new HashMap<String,Long>();
        for(var offer:spending.shop(new SpendingService.LocalContext(0,state,budget,location),"property").offers())
            prices.put(offer.id(),offer.price().movePointRight(2).longValueExact());
        world.propertyPrices=Map.copyOf(prices);
        AdultDecisionPacing.restoreCounts(world);
        return world;
    }
    private boolean eligible(AdultStoryCatalog.Definition d,AdultWorld w) {
        if(!AdultConditions.test(d.condition(),w))return false;
        if(d.participant()!=null&&!Set.of("new-partner","friend").contains(d.participant())&&AdultConditions.participant(d.participant(),w)==null)return false;
        for(var s:w.stories)if(s.template().equals(d.id())&&(s.status().equals("ACTIVE")||w.month()-s.beganMonth()<d.cooldownMonths()
                ||(s.beganMonth()-1)/12==(w.month()-1)/12))return false;
        return true;
    }
    private int weight(AdultStoryCatalog.Definition d,AdultWorld w) {
        int value=d.weight();
        if(d.domain().equals("HEALTH")&&w.age()>=50)value*=2;
        if(d.domain().equals("EDUCATION")&&w.age()<30)value*=2;
        if(d.id().equals("employment")&&!w.employed()&&RetirementPolicy.canWork(w))value*=4;
        if(d.domain().equals("HEALTH")&&w.state.stats().health()<50)value*=3;
        if(d.condition().equals("debt")&&w.budget.balance()<0)value*=3;
        if(d.domain().equals("RELATIONSHIPS")&&w.traits.sociability()>65)value+=3;
        if(w.stories.stream().noneMatch(s -> s.template().equals(d.id())))value*=2;
        return value;
    }
    private void start(AdultWorld w,AdultStoryCatalog.Definition d) {
        var participant=AdultConditions.participant(d.participant(),w);
        if("new-partner".equals(d.participant())) {
            String sex=FamilySystem.heroSex(w).equals("FEMALE")?"MALE":"FEMALE";
            int age=Math.max(18,w.age()+w.random.nextInt(-5,6));
            participant=w.person(w.newPerson("partner",sex,w.month()-age*12,List.of()));
        } else if("friend".equals(d.participant())&&participant==null) {
            participant=w.person(w.newPerson("friend",w.chance(.5)?"FEMALE":"MALE",w.month()-Math.max(18,w.age()+w.random.nextInt(-8,9))*12,List.of()));
        }
        var story=new Story(w.id("story"),d.id(),"CHOICE","ACTIVE",w.month(),w.month(),null,
                participant==null?List.of():List.of(participant.id()),Map.of(),List.of("CHOICE"));
        var p=new StoryProgress(story);var before=w.state.stats();var old=w.sim;
        if(d.trigger()!=null)act(d.trigger(),w,p);
        if(w.state.stats().health()<=0) {
            replace(w,finish(story,"CANCELLED",w.month(),p.variables));
            w.event(d.title(),render(d.text(),participant==null?"":participant.name()),before,old);
            return;
        }
        if(d.id().equals("bereavement")&&participant!=null)w.flags.add("grieved:"+participant.id());
        offer(w,story,d.choices(),p.variables,d.title());
        w.event(d.title(),render(d.text(),participant==null?"":participant.name()),before,old,
                link(w,story,d,"OFFER",d.trigger(),w.state.pendingDecision(),null));
    }
    private void offer(AdultWorld w,Story story,List<AdultStoryCatalog.Choice> choices,Map<String,String> vars,String title) {
        w.sim=DecisionPacing.recordOffer(w.state,w.sim,w.month());
        var options=new ArrayList<LifeState.Option>();
        for(var choice:choices) {
            long cost=w.cost(choice.costUnits()); vars.put("cost:"+choice.id(),String.valueOf(cost));
            long required=Math.max(cost,requiredCash(w,choice.action()));
            if(w.budget.available()>=required)options.add(new LifeState.Option(choice.id(),choice.label()+(required>0?" — "+LocalBudget.money(required).toPlainString()+" ₽":"")));
        }
        replace(w,new Story(story.id(),story.template(),story.stage(),story.status(),story.beganMonth(),story.dueMonth(),null,story.participants(),vars,story.visitedStages()));
        w.pending=new Pending(story.id(),story.stage(),options.stream().map(LifeState.Option::id).toList());
        w.decision(new LifeState.Decision(story.id()+"-"+story.stage(),title,options));
    }
    private void review(AdultWorld w,Story story) {
        var d=catalog.get(story.template());boolean first=story.stage().equals("REVIEW");
        String action=first?story.variables().getOrDefault("reviewAction",d.reviewAction()):d.laterAction();
        if("cohabit".equals(action)) {
            var choice=new Story(story.id(),story.template(),"COHABIT_CHOICE","ACTIVE",story.beganMonth(),w.month(),null,story.participants(),story.variables(),story.visitedStages());
            offer(w,choice,choices(d,choice),new HashMap<>(story.variables()),"Начать жить вместе?");
            w.event("Общий быт","Вы обсуждаете, готовы ли разделить дом, повседневные расходы и ответственность.",w.state.stats(),w.sim,
                    link(w,choice,d,"OFFER",action,w.state.pendingDecision(),null));return;
        }
        var p=new StoryProgress(story);var before=w.state.stats();var old=w.sim;
        var result=act(action,w,p);var visited=new ArrayList<>(story.visitedStages());visited.add(story.stage());
        var updated=new Story(story.id(),story.template(),"LATER","ACTIVE",story.beganMonth(),w.month()+d.laterMonths(),null,story.participants(),p.variables,visited);
        if(!result.proceed()||!first||d.laterAction()==null||p.variables.containsKey("noLater"))updated=finish(updated,"COMPLETED",w.month(),p.variables);
        replace(w,updated);w.event(first&&story.variables().containsKey("reviewAction")?"Результат подготовки":first?d.reviewTitle():d.laterTitle(),result.text(),before,old,
                link(w,updated,d,"OUTCOME",action,null,null));
    }
    private void deferQuestions(AdultWorld w) {
        if(DecisionPacing.allowed(w.state,w.sim,w.month()))return;
        for(var s:List.copyOf(w.stories)) {
            if(!s.status().equals("ACTIVE")||s.dueMonth()>w.month())continue;
            var d=catalog.get(s.template());
            String action=s.stage().equals("REVIEW")?s.variables().getOrDefault("reviewAction",d.reviewAction()):d.laterAction();
            if("cohabit".equals(action)) replace(w,new Story(s.id(),s.template(),s.stage(),s.status(),s.beganMonth(),w.month()+2,
                    s.endedMonth(),s.participants(),s.variables(),s.visitedStages()));
        }
    }
    private List<AdultStoryCatalog.Choice> choices(AdultStoryCatalog.Definition d,Story s) {
        return s.stage().equals("COHABIT_CHOICE")?List.of(new AdultStoryCatalog.Choice("move","Начать совместный быт","cohabit",0),
                new AdultStoryCatalog.Choice("stay","Пока жить отдельно","pass",0)):d.choices();
    }
    private StoryProgress.Result act(String action,AdultWorld w,StoryProgress p) {
        if(action.equals("pass"))return StoryProgress.Result.end("Ты решаешь сейчас не менять этот аспект жизни. К некоторым возможностям можно будет вернуться позже.");
        if(action.equals("wait"))return StoryProgress.Result.next("Ты оставляешь время, чтобы оценить последствия.");
        if(action.equals("apply_job")&&w.flags.remove("job-referral")) {
            action="job_referral";
            var source=w.flags.stream().filter(f->f.startsWith("job-referral-source:")).sorted().findFirst();
            source.ifPresent(f->p.put("causedByStory",f.substring("job-referral-source:".length())));
            w.flags.removeIf(f->f.startsWith("job-referral-source:"));
        }
        if(ContactSystem.ACTIONS.contains(action))return contacts.act(action,w,p);
        if(LaterLifeSystem.ACTIONS.contains(action))return laterLife.act(action,w,p);
        if(CareerSystem.ACTIONS.contains(action))return career.act(action,w,p);
        if(FamilySystem.ACTIONS.contains(action))return family.act(action,w,p);
        if(HealthSystem.ACTIONS.contains(action))return health.act(action,w,p);
        if(AdultEconomy.ACTIONS.contains(action))return economy.act(action,w,p);
        throw new IllegalStateException("Unknown action: "+action);
    }
    private void cancelInvalid(AdultWorld w) {
        for(var s:List.copyOf(w.stories))if(s.status().equals("ACTIVE")&&!Set.of("inheritance","bereavement","family-planning").contains(s.template())) {
            boolean lost=s.participants().stream().anyMatch(id -> w.person(id)!=null&&w.person(id).alive()&&!ContactSystem.canInteract(w,id));
            boolean dead=s.participants().stream().anyMatch(id -> w.person(id)==null||!w.person(id).alive());
            if(dead || (lost && !s.template().equals("restore-contact"))) {
                replace(w,finish(s,"CANCELLED",w.month(),s.variables()));
                w.event("Планы изменились","Продолжение истории «"+catalog.get(s.template()).title()+"» отменено "
                        +(dead?"после утраты участника.":"после прекращения общения."),w.state.stats(),w.sim,
                        link(w,s,catalog.get(s.template()),"CANCELLED",null,null,null));
            }
        }
    }
    private void validatePurchaseChoice(AdultWorld w,String action) {
        long amount=requiredCash(w,action);
        if(w.budget.available()<amount)throw conflict("SPENDING_INSUFFICIENT_FUNDS","Недостаточно средств для этого решения.");
    }
    private com.life.life_backend.highlights.EventLink link(AdultWorld w, Story story, AdultStoryCatalog.Definition definition,
            String phase, String action, LifeState.Decision decision, AdultStoryCatalog.Choice choice) {
        String source=story.variables().get("causedByStory");
        return new com.life.life_backend.highlights.EventLink(story.id(),story.template(),definition.domain(),phase,w.month(),
                decision==null?null:decision.id(),choice==null?null:choice.id(),choice==null?null:choice.label(),action,
                decision==null?List.of():decision.options(),story.participants(),source==null?List.of():List.of(source));
    }
    private static long requiredCash(AdultWorld w,String action) {
        return switch(action){case "buy_home"->w.propertyPrice("house");case "mortgage"->w.propertyPrice("house")/5;case "buy_vehicle"->w.propertyPrice("motorcycle");case "invest"->w.cost(10);default->0;};
    }
    private static Story finish(Story s,String status,int month,Map<String,String> vars){return new Story(s.id(),s.template(),s.stage(),status,s.beganMonth(),s.dueMonth(),month,s.participants(),vars,s.visitedStages());}
    private static void replace(AdultWorld w,Story s){w.stories.removeIf(old -> old.id().equals(s.id()));w.stories.add(s);}
    private static String render(String text,String name){return text.replace("{person}",name);}
    public Transition completeIfHealthDepleted(LifeState state,LocalSimulation sim,AdultState adult,LocalBudget budget,FinanceView.Location location) {
        return transition(world(state,sim,adult,budget,location));
    }
    private DeathCauseModel.Context causeContext(AdultWorld w) {
        return new DeathCauseModel.Context(w.diseases.stream().map(d -> new DeathCauseModel.Condition(
                d.id(),d.kind(),d.onsetMonth(),d.severity(),d.endedMonth()==null&&!d.stage().equals("RECOVERED"),d.chronic(),d.treatment().equals("SUPERVISED"))).toList(),
                w.employed()?w.career.risk():0);
    }
    private Transition transition(AdultWorld w){
        var checked=mortality.completeIfHealthDepleted(w.state,w.sim,causeContext(w));
        w.state=checked.state();w.sim=checked.simulation();
        if(w.state.status()==LifeState.Status.COMPLETE) {
            w.stories.replaceAll(s -> s.status().equals("ACTIVE")?finish(s,"CANCELLED",w.month(),s.variables()):s);
            w.pending=null;w.flags.remove("pregnancy");CareerSystem.leave(w,"Жизнь завершена");
        }
        return new Transition(w.state,w.sim,w.snapshot(),w.budget);
    }
    private static ApiFailure conflict(String code,String text){return new ApiFailure(HttpStatus.CONFLICT,code,text);}
    public record Transition(LifeState state,LocalSimulation simulation,AdultState adult,LocalBudget budget){}
}
