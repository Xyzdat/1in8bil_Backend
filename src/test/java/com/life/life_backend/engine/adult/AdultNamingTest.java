package com.life.life_backend.engine.adult;

import com.life.life_backend.engine.names.*;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.economy.*;
import com.life.life_backend.statistics.CountryCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AdultNamingTest {
    final ObjectMapper json = new ObjectMapper();
    final CountryCatalog countries = new CountryCatalog(json);
    AdultNamingTest() throws Exception { }

    @Test void newNpcsAndDescendantsHaveInheritedNamesAndRestoreExactly() {
        var world = world(true, "RU");
        String partner = world.newPerson("partner", "FEMALE", 0, List.of());
        String daughter = world.newPerson("child", "FEMALE", 360, List.of("character",partner));
        var hero = world.sim.naming().people().get("character");
        var child = world.sim.naming().people().get(daughter);
        assertThat(child.family()).isEqualTo(hero.family());
        assertThat(world.person(daughter).name()).isEqualTo(child.given() + " " + hero.family().getFirst().female());
        String spouse = world.newPerson("in-law", "MALE", 360, List.of());
        String grandson = world.newPerson("grandchild", "MALE", 660, List.of(daughter,spouse));
        assertThat(world.sim.naming().people().get(grandson).family()).isEqualTo(world.sim.naming().people().get(spouse).family());
        var restored = new AdultWorld(world.state, json.readValue(json.writeValueAsString(world.sim),LocalSimulation.class),
                world.budget,json.readValue(json.writeValueAsString(world.snapshot()),AdultState.class),world.location,countries,1);
        var continued = new AdultWorld(world.state,world.sim,world.budget,world.snapshot(),world.location,countries,1);
        String next1 = continued.newPerson("child","MALE",370,List.of("character",partner));
        String next2 = restored.newPerson("child","MALE",370,List.of("character",partner));
        assertThat(next1).isEqualTo(next2);
        assertThat(continued.sim).isEqualTo(restored.sim);
        assertThat(continued.snapshot()).isEqualTo(restored.snapshot());
    }

    @Test void surnameDrawsDoNotChangeNpcTraitsOrSimulationRandomStream() {
        var legacy = world(false,"JP"); var named = world(true,"JP");
        String oldId = legacy.newPerson("friend","FEMALE",0,List.of());
        String newId = named.newPerson("friend","FEMALE",0,List.of());
        assertThat(newId).isEqualTo(oldId);
        assertThat(named.npcs).isEqualTo(legacy.npcs);
        assertThat(named.random.nextLong()).isEqualTo(legacy.random.nextLong());
        assertThat(legacy.sim.naming()).isNull();
        assertThat(countries.get("JPN").femaleNames()).contains(legacy.person(oldId).name());
        assertThat(named.sim.naming().people().get(newId).countryIso3()).isEqualTo("JPN");
        assertThat(named.person(newId).name()).endsWith(" " + legacy.person(oldId).name());
    }

    @Test void absentNamingInLegacyJsonIsNeverUpgradedByEvolutionOrNpcCreation() {
        var world = world(false,"RU");
        var stored = (tools.jackson.databind.node.ObjectNode)json.valueToTree(world.sim); stored.remove("naming");
        var restored = json.treeToValue(stored,LocalSimulation.class);
        world.sim = restored.evolve(362,restored.family(),restored.skills(),restored.flags(),restored.lastEvents(),List.of(),null,null);
        world.newPerson("partner","FEMALE",0,List.of());
        assertThat(world.sim.naming()).isNull();
        assertThat(world.sim.family().getFirst().name()).isEqualTo("Мария");
    }

    private AdultWorld world(boolean named, String country) {
        var people = List.of(new LocalSimulation.Person("mother","mother","Мария",-300,true,70,70));
        var sim = new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),360,
                people,Map.of(),Set.of(),Map.of(),List.of(),null);
        if (named) sim = sim.withNaming(PersonalNames.birth(countries.names(),"RUS","Артём","MALE",people,47));
        return new AdultWorld(new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(80,80,60,70,50,0),null,List.of()),sim,
                new LocalBudget(360,100000,List.of(),List.of()),null,new FinanceView.Location(country,"Страна",null),countries,1);
    }
}
