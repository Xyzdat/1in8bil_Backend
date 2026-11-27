package com.life.life_backend.engine.focus;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Immutable authored game rules, independent of demographic data and external engines. */
public final class FocusCatalog {
    private static final Catalog CATALOG=load();
    private FocusCatalog() { }
    private static Catalog load() {
        try(var in=new ClassPathResource("focus/year-focus-1.json").getInputStream()) {
            var file=new ObjectMapper().readValue(in,Catalog.class);
            if(!FocusState.VERSION.equals(file.modelVersion())||file.weightMultiplier()!=3||file.options().size()!=8)
                throw new IllegalStateException("Invalid focus catalog");
            var ids=new HashSet<String>();
            for(var option:file.options()) if(!ids.add(option.id())||option.minAge()<0||option.maxAge()<option.minAge()
                    ||!Set.of("health","happiness","intelligence","relationships").containsAll(option.stats().keySet()))
                throw new IllegalStateException("Invalid focus option");
            return file;
        }catch(Exception failure){throw new IllegalStateException("Cannot load focus rules",failure);}
    }
    public static List<Definition> all(){return CATALOG.options();}
    public static Definition get(String id){return all().stream().filter(option->option.id().equals(id)).findFirst().orElse(null);}
    public static int multiplier(){return CATALOG.weightMultiplier();}
    private record Catalog(String modelVersion,int weightMultiplier,List<Definition> options) { }
    public record Definition(String id,String title,String description,int minAge,int maxAge,Map<String,Integer> stats,
            Map<String,Integer> skills,int closeness,List<String> roles,List<String> domains,List<String> events,List<String> daily) {
        public Definition { stats=Map.copyOf(stats);skills=Map.copyOf(skills);roles=List.copyOf(roles);domains=List.copyOf(domains);events=List.copyOf(events);daily=List.copyOf(daily); }
        public boolean allowed(int age){return age>=minAge&&age<=maxAge;}
        public String title(int age){return id.equals("friends")&&age>=18?"Друзья и общение":id.equals("home")&&age>=18?"Семья и дом":title;}
    }
}
