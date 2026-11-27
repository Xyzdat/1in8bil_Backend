package com.life.life_backend.engine.local;

import java.util.*;

/** Server-only state. Queued consequences are deliberately absent from the public view. */
public record LocalSimulation(long seed, String engineVersion, String statisticsRelease,
        BirthRequest request, int month, List<Person> family, Map<String, Integer> skills,
        Set<String> flags, Map<String, Integer> lastEvents, List<Scheduled> scheduled,
        String pendingTemplate, BirthFacts birthFacts, Death death,
        com.life.life_backend.statistics.SocioeconomicCalibration calibration,
        com.life.life_backend.engine.names.NamingState naming,
        com.life.life_backend.engine.focus.FocusState focus) {
    public static final String VERSION = "childhood-2";
    public static final String STATISTICS = "wdi-2023-20261111";
    public LocalSimulation {
        family = List.copyOf(family); skills = Map.copyOf(skills); flags = Set.copyOf(flags);
        lastEvents = Map.copyOf(lastEvents); scheduled = List.copyOf(scheduled);
    }
    public LocalSimulation(long seed,String version,String statisticsRelease,BirthRequest request,int month,
            List<Person> family,Map<String,Integer> skills,Set<String> flags,Map<String,Integer> lastEvents,
            List<Scheduled> scheduled,String pendingTemplate){
        this(seed,version,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,null,null);
    }
    /** Missing field in an old save keeps legacy family/economy rules. */
    public LocalSimulation(long seed,String version,String statisticsRelease,BirthRequest request,int month,
            List<Person> family,Map<String,Integer> skills,Set<String> flags,Map<String,Integer> lastEvents,
            List<Scheduled> scheduled,String pendingTemplate,BirthFacts birthFacts,Death death) {
        this(seed,version,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,birthFacts,death,null);
    }
    /** Missing naming metadata preserves the legacy name generation, including future NPCs. */
    public LocalSimulation(long seed,String version,String statisticsRelease,BirthRequest request,int month,
            List<Person> family,Map<String,Integer> skills,Set<String> flags,Map<String,Integer> lastEvents,
            List<Scheduled> scheduled,String pendingTemplate,BirthFacts birthFacts,Death death,
            com.life.life_backend.statistics.SocioeconomicCalibration calibration) {
        this(seed,version,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,birthFacts,death,calibration,null);
    }
    public LocalSimulation evolve(int time,List<Person> people,Map<String,Integer> newSkills,Set<String> newFlags,
            Map<String,Integer> events,List<Scheduled> queue,String pending,Death ended){
        return new LocalSimulation(seed,VERSION,statisticsRelease,request,time,people,newSkills,newFlags,events,queue,pending,birthFacts,ended,calibration,naming,focus);
    }
    public LocalSimulation withBirthFacts(BirthFacts facts){
        return new LocalSimulation(seed,engineVersion,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,facts,death,calibration,naming,focus);
    }
    public LocalSimulation withNaming(com.life.life_backend.engine.names.NamingState names) {
        return new LocalSimulation(seed,engineVersion,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,birthFacts,death,calibration,names,focus);
    }
    /** No automatic opt-in for an existing save missing focus. */
    public LocalSimulation(long seed,String version,String statisticsRelease,BirthRequest request,int month,
            List<Person> family,Map<String,Integer> skills,Set<String> flags,Map<String,Integer> lastEvents,
            List<Scheduled> scheduled,String pendingTemplate,BirthFacts birthFacts,Death death,
            com.life.life_backend.statistics.SocioeconomicCalibration calibration,com.life.life_backend.engine.names.NamingState naming) {
        this(seed,version,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,birthFacts,death,calibration,naming,null);
    }
    public LocalSimulation withFocus(com.life.life_backend.engine.focus.FocusState value) {
        return new LocalSimulation(seed,engineVersion,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,birthFacts,death,calibration,naming,value);
    }
    public record Death(int elapsedMonths,int age,String cause,String model,String note,DeathCauseModel.Details causeDetails){
        public Death(int elapsedMonths,int age,String cause,String model,String note){
            this(elapsedMonths,age,cause,model,note,null);
        }
    }
    public record BirthRequest(String countryIso3, int birthYear) { }
    public record Person(String id, String role, String name, int birthMonth, boolean alive,
                         int closeness, int trust, String sex, String occupation, Integer diedMonth, Death death) {
        public Person(String id,String role,String name,int birthMonth,boolean alive,int closeness,int trust,String sex,String occupation,Integer diedMonth){
            this(id,role,name,birthMonth,alive,closeness,trust,sex,occupation,diedMonth,null);
        }
        public Person(String id,String role,String name,int birthMonth,boolean alive,int closeness,int trust,String sex,String occupation){
            this(id,role,name,birthMonth,alive,closeness,trust,sex,occupation,null);
        }
        public Person(String id,String role,String name,int birthMonth,boolean alive,int closeness,int trust){
            this(id,role,name,birthMonth,alive,closeness,trust,role.equals("father")?"MALE":"FEMALE",null);
        }
    }
    public record Scheduled(String templateId, int dueMonth, String sourceDecisionId) {
        public Scheduled(String templateId,int dueMonth){this(templateId,dueMonth,null);}
    }
    public Person participant(String role) {
        var candidates = family.stream().filter(p -> p.alive() && p.role().equals(role)).toList();
        if (candidates.isEmpty()) return null;
        return candidates.get(new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * month)).nextInt(candidates.size()));
    }
    public View view() {
        return view(null);
    }
    public View view(com.life.life_backend.engine.adult.AdultState adult) {
        return new View(engineVersion, statisticsRelease, birthFacts == null ? 2023 : 2026, month, month % 12, 2,
                family.stream().map(p -> new Relative(p.id(), p.role(), p.name(),
                        ((p.diedMonth()==null?month:p.diedMonth()) - p.birthMonth()) / 12, p.alive(), p.closeness(), p.trust(),p.sex(),p.occupation(),
                        adult == null || !p.alive() ? null : adult.contacts().get(p.id()),p.death())).toList(), skills,
                List.of("Country weights are conditional on the supported countries of the saved statistics release; see birthFacts.selectionProbability.",
                        "New v2 births use UN WPP 2024 projections for 2026 (including sex ratio), WDI 2023 GDP and urban share. Requested birth years 2023–2026 use the same reference year.",
                        "Urban population share approximates urban births.",
                        calibration == null ? "Family composition, parent ages, occupations, wealth and housing are legacy game assumptions."
                            : "Family composition and housing utilities use pinned UN household/WDI proxies; ages, sibling-count shape, dwelling form and closeness remain assumptions. See character.birthFacts and socioeconomic-statistics.md.",
                        "Religion sampling uses 2020 population shares; language sampling uses CLDR fluency estimates, not household language statistics.",
                        calibration == null || calibration.birthEconomy() == null ? "Birth wealth and parent occupations retain their saved legacy assumptions."
                            : "Birth resources use WDI quintile income/consumption shares relative to the country mean; quintiles have equal population mass. Parent occupational groups use ILO employed-population shares, independently of wealth. New birth-economy-2 lives add explicitly authored specialties; China uses pinned ILOEST Nov. 2025 modelled groups including combined 6+9. Missing occupations remain unspecified; see parent-occupations.md and birth-economy.md.",
                        "Mortality uses UN WPP 2024 projections; beyond 2100 the 2100 schedule is held constant. Health modifiers are game assumptions.",
                        "Specific death causes use death-causes-1 game rules, not country cause-of-death statistics. They do not add another mortality draw."), death,
                        com.life.life_backend.engine.adult.AdultView.from(adult,month),com.life.life_backend.engine.focus.YearFocus.view(this,adult));
    }
    public record Relative(String id, String role, String name, int age, boolean alive, int closeness, int trust,String sex,String occupation,
                           com.life.life_backend.engine.adult.ContactState contact,Death death) { }
    public record View(String engineVersion, String statisticsRelease, int statisticsYear,
                       int elapsedMonths, int monthOfYear, int stepMonths,
                       List<Relative> family, Map<String, Integer> skills, List<String> assumptions,Death death,
                       com.life.life_backend.engine.adult.AdultView adult,com.life.life_backend.engine.focus.YearFocus.View yearlyFocus) { }
}
