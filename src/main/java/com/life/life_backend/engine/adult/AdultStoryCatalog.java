package com.life.life_backend.engine.adult;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Component
public class AdultStoryCatalog {
    private final List<Definition> definitions;
    public AdultStoryCatalog(ObjectMapper json) throws Exception {
        try(var in=new ClassPathResource("events/adult-1.json").getInputStream()) { definitions=List.of(json.readValue(in,Definition[].class)); }
        var ids=new HashSet<String>(); var actions=new HashSet<>(Set.of("pass","wait"));
        actions.addAll(CareerSystem.ACTIONS); actions.addAll(FamilySystem.ACTIONS);actions.addAll(HealthSystem.ACTIONS);actions.addAll(AdultEconomy.ACTIONS);
        actions.addAll(ContactSystem.ACTIONS);
        for(var d:definitions) {
            if(!ids.add(d.id())||d.weight()<1||d.cooldownMonths()<2||!AdultConditions.KEYS.contains(d.condition())||d.reviewMonths()<2||d.choices().size()<2)
                throw new IllegalStateException("Invalid adult story: "+d.id());
            var used=new HashSet<String>();
            for(var c:d.choices())if(!used.add(c.id())||!actions.contains(c.action())||c.costUnits()<0)throw new IllegalStateException("Invalid adult choice: "+d.id());
            if(!actions.contains(d.reviewAction())||(d.laterAction()!=null&&!actions.contains(d.laterAction()))||(d.trigger()!=null&&!actions.contains(d.trigger())))
                throw new IllegalStateException("Invalid adult action: "+d.id());
            if(d.laterAction()!=null&&d.laterMonths()<2)throw new IllegalStateException("Invalid adult delay");
        }
    }
    public List<Definition> all(){return definitions;}
    public Definition get(String id){return definitions.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();}
    public record Definition(String id,String domain,int weight,int cooldownMonths,String condition,String participant,
            String trigger,String title,String text,List<Choice> choices,int reviewMonths,String reviewTitle,String reviewAction,
            int laterMonths,String laterTitle,String laterAction){}
    public record Choice(String id,String label,String action,int costUnits){}
}
