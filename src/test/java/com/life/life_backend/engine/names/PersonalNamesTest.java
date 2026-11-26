package com.life.life_backend.engine.names;

import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.statistics.CountryCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PersonalNamesTest {
    final ObjectMapper json = new ObjectMapper();
    final CountryCatalog countries = new CountryCatalog(json);
    PersonalNamesTest() throws Exception { }

    @Test void allCountriesHaveDeterministicCompleteNamesWithoutChangingGivenNames() {
        for (String iso : countries.codes()) for (int seed = 0; seed < 30; seed++) {
            var country = countries.get(iso);
            var relatives = family(country.femaleNames().getFirst(), country.maleNames().getFirst());
            String given = country.femaleNames().getLast();
            var names = PersonalNames.birth(countries.names(), iso, given, "FEMALE", relatives, seed);
            assertThat(names).isEqualTo(PersonalNames.birth(countries.names(), iso, given, "FEMALE", relatives, seed));
            assertThat(names.people()).hasSize(5);
            assertThat(names.people().get("character").given()).isEqualTo(given);
            for (var name : names.people().values()) {
                assertThat(name.countryIso3()).isEqualTo(iso);
                assertThat(name.family()).isNotEmpty();
                assertThat(name.display()).contains(" ", name.given()).hasSizeLessThanOrEqualTo(100);
                assertThat(name.display()).doesNotContain("null", "  ");
            }
            assertThat(names.people().get("brother").family()).isEqualTo(names.people().get("character").family());
            assertThat(names.people().get("sister").family()).isEqualTo(names.people().get("character").family());
        }
    }

    @Test void russianFamilyUsesOneSurnameWithExplicitMaleAndFemaleForms() {
        var names = PersonalNames.birth(countries.names(), "RUS", "Анна", "FEMALE", family("Мария","Илья"), 42);
        var part = names.people().get("father").family().getFirst();
        assertThat(names.people().values()).allMatch(name -> name.family().equals(List.of(part)));
        assertThat(names.people().get("character").display()).isEqualTo("Анна " + part.female());
        assertThat(names.people().get("mother").display()).isEqualTo("Мария " + part.female());
        assertThat(names.people().get("father").display()).isEqualTo("Илья " + part.male());
        var singleMother = PersonalNames.birth(countries.names(), "RUS", "Артём", "MALE", family("Мария","Илья").subList(0,1), 42);
        assertThat(singleMother.people().get("character").family()).isEqualTo(singleMother.people().get("mother").family());
    }

    @Test void eastAsianDisplayOrderDoesNotAssumeFirstWordIsGivenName() {
        for (String iso : List.of("JPN","CHN","KOR","VNM")) {
            var names = PersonalNames.birth(countries.names(), iso, "Имя", "FEMALE", family("Мама","Папа"), 44);
            var name = names.people().get("character");
            assertThat(name.display()).isEqualTo(name.family().getFirst().female() + " Имя");
        }
    }

    @Test void doubleNamesFollowParentComponentsAndPatronymicsUseFathersGivenName() {
        for (String iso : List.of("MEX","ARG","COL","PER","CHL","BRA")) {
            var names = PersonalNames.birth(countries.names(), iso, "Ребёнок", "MALE", family("Мама","Папа"), 73);
            var father = names.people().get("father").family();
            var mother = names.people().get("mother").family();
            assertThat(names.people().get("character").family()).isEqualTo(iso.equals("BRA")
                    ? List.of(mother.getLast(), father.getLast()) : List.of(father.getFirst(), mother.getFirst()));
        }
        for (String iso : List.of("ETH","MYS")) {
            var names = PersonalNames.birth(countries.names(), iso, "Дочь", "FEMALE", family("Мать","Отец"), 52);
            assertThat(names.people().get("character").display()).isEqualTo("Дочь Отец");
            var descendant = PersonalNames.newPerson(countries.names(), names, iso, "Внук", "MALE", List.of("character"), 52, 400, "child");
            assertThat(descendant.display()).startsWith("Внук ").doesNotEndWith(" Отец");
        }
    }

    @Test void serializationAndEditsPreserveKnownComponentsWithoutParsingCustomNames() {
        var family = family("Мария","Илья");
        var names = PersonalNames.birth(countries.names(), "RUS", "Анна", "FEMALE", family, 42);
        assertThat(json.readValue(json.writeValueAsString(names), NamingState.class)).isEqualTo(names);
        assertThat(names.keepMatchingName("character", names.people().get("character").display())).isSameAs(names);
        var renamed = names.keepMatchingName("character", "Произвольное имя с пробелами");
        assertThat(renamed.people()).doesNotContainKey("character");
        assertThat(renamed.people()).containsKey("mother");
        var kept = names.reconcile(names.people().get("character").display(), "FEMALE", List.of());
        assertThat(kept.people()).containsOnlyKeys("character");
    }

    private List<LocalSimulation.Person> family(String mother, String father) {
        return List.of(new LocalSimulation.Person("mother","mother",mother,-300,true,70,70),
                new LocalSimulation.Person("father","father",father,-324,true,70,70),
                new LocalSimulation.Person("brother","brother","Брат",-48,true,70,70,"MALE",null),
                new LocalSimulation.Person("sister","sister","Сестра",-24,true,70,70,"FEMALE",null));
    }
}
