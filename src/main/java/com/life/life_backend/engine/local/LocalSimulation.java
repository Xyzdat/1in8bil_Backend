package com.life.life_backend.engine.local;

import java.util.*;

/** Server-only state. Queued consequences are deliberately absent from the public view. */
public record LocalSimulation(long seed, String engineVersion, String statisticsRelease,
        BirthRequest request, int month, List<Person> family, Map<String, Integer> skills,
        Set<String> flags, Map<String, Integer> lastEvents, List<Scheduled> scheduled,
        String pendingTemplate, BirthFacts birthFacts, Death death) {
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
    public LocalSimulation evolve(int time,List<Person> people,Map<String,Integer> newSkills,Set<String> newFlags,
            Map<String,Integer> events,List<Scheduled> queue,String pending,Death ended){
        return new LocalSimulation(seed,VERSION,statisticsRelease,request,time,people,newSkills,newFlags,events,queue,pending,birthFacts,ended);
    }
    public LocalSimulation withBirthFacts(BirthFacts facts){
        return new LocalSimulation(seed,engineVersion,statisticsRelease,request,month,family,skills,flags,lastEvents,scheduled,pendingTemplate,facts,death);
    }
    public record Death(int elapsedMonths,int age,String cause,String model,String note){}
    public record BirthRequest(String countryIso3, int birthYear) { }
    public record Person(String id, String role, String name, int birthMonth, boolean alive,
                         int closeness, int trust, String sex, String occupation, Integer diedMonth) {
        public Person(String id,String role,String name,int birthMonth,boolean alive,int closeness,int trust,String sex,String occupation){
            this(id,role,name,birthMonth,alive,closeness,trust,sex,occupation,null);
        }
        public Person(String id,String role,String name,int birthMonth,boolean alive,int closeness,int trust){
            this(id,role,name,birthMonth,alive,closeness,trust,role.equals("father")?"MALE":"FEMALE",null);
        }
    }
    public record Scheduled(String templateId, int dueMonth) { }
    public Person participant(String role) {
        var candidates = family.stream().filter(p -> p.alive() && p.role().equals(role)).toList();
        if (candidates.isEmpty()) return null;
        return candidates.get(new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * month)).nextInt(candidates.size()));
    }
    public View view() {
        return new View(engineVersion, statisticsRelease, birthFacts == null ? 2023 : 2026, month, month % 12, 2,
                family.stream().map(p -> new Relative(p.id(), p.role(), p.name(),
                        ((p.diedMonth()==null?month:p.diedMonth()) - p.birthMonth()) / 12, p.alive(), p.closeness(), p.trust(),p.sex(),p.occupation())).toList(), skills,
                List.of("Country weights are conditional on the supported countries of the saved statistics release; see birthFacts.selectionProbability.",
                        "New v2 births use UN WPP 2024 projections for 2026 (including sex ratio), WDI 2023 GDP and urban share. Requested birth years 2023–2026 use the same reference year.",
                        "Urban population share approximates urban births.",
                        "Family composition, parent ages, occupations, wealth and housing are game assumptions; see character.birthFacts.",
                        "Religion sampling uses 2020 population shares; language sampling uses CLDR fluency estimates, not household language statistics.",
                        "Mortality uses UN WPP 2024 projections; beyond 2100 the 2100 schedule is held constant. Health modifiers are game assumptions."), death);
    }
    public record Relative(String id, String role, String name, int age, boolean alive, int closeness, int trust,String sex,String occupation) { }
    public record View(String engineVersion, String statisticsRelease, int statisticsYear,
                       int elapsedMonths, int monthOfYear, int stepMonths,
                       List<Relative> family, Map<String, Integer> skills, List<String> assumptions,Death death) { }
}
