package com.life.life_backend.session;

import com.life.life_backend.engine.*;
import com.life.life_backend.engine.local.*;
import com.life.life_backend.statistics.*;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
final class BirthCustomizationService {
    private final CountryCatalog countries;
    private final BirthContextFactory context;
    private final LabourCalibrationFactory labour;
    private final BirthFamilyEditor families = new BirthFamilyEditor();
    BirthCustomizationService(CountryCatalog countries, BirthContextFactory context, LabourCalibrationFactory labour) { this.countries = countries; this.context = context; this.labour = labour; }

    Result edit(CharacterProfile profile, BirthDetails details, LifeState state, LocalSimulation sim,
                LocalSimulation.BirthRequest originalRequest, CharacterChanges changes) {
        if (sim == null) throw conflict("BIRTH_CUSTOMIZATION_UNSUPPORTED", "Birth and family editing requires a generated local life.");
        if (sim.month() != 0 || state.status() != LifeState.Status.ACTIVE || state.pendingDecision() != null)
            throw conflict("BIRTH_CONFIGURATION_LOCKED", "Birth and family can only be changed before the first life step.");
        var place = birthplace(profile.birthplace(), changes.birthplace());
        boolean countryChanged = !place.countryCode().equals(profile.birthplace().countryCode());
        String iso3 = countries.iso3(place.countryCode());
        String sex = changes.sex() == null ? ("Мужчина".equals(profile.gender()) ? "MALE" : "FEMALE") : changes.sex().name();
        var members = families.members(sim.family(), changes.family() == null ? null : changes.family().members());
        var background = families.background(profile.background(), changes.family(), members);
        var updatedDetails = families.details(details, changes, members);
        var updatedProfile = new CharacterProfile(profile.name(), sex.equals("MALE") ? "Мужчина" : "Женщина",
                changes.birthYear() == null ? profile.birthYear() : changes.birthYear(), place, background);
        boolean selected = changes.birthplace() != null && changes.birthplace().countryCode() != null || sim.request().countryIso3() != null;
        var facts = context.create(iso3, sex, selected, background.wealth(), background.housing(), background.relationships(), background.religion(), background.language());
        facts = provenance(facts, sim.birthFacts(), changes.family(), countryChanged || changes.birthYear() != null);
        var flags = new HashSet<>(sim.flags());
        flags.remove("urban"); flags.remove("rural");
        if (updatedDetails.placeType() != null) flags.add(updatedDetails.placeType() == BirthDetails.PlaceType.URBAN ? "urban" : "rural");
        // The effective request controls the simulation calendar; the original creation request is saved separately.
        var effectiveRequest = new LocalSimulation.BirthRequest(selected ? iso3 : null, updatedProfile.birthYear());
        var calibration = sim.calibration();
        if (calibration != null && changes.birthYear() != null)
            calibration = new SocioeconomicCalibration(calibration.modelVersion(), calibration.familyRelease(), calibration.workRelease(),
                    calibration.family(), labour.snapshot(updatedProfile.birthYear()), calibration.birthEconomy());
        var updatedSim = new LocalSimulation(sim.seed(), sim.engineVersion(), CountryExpansionImporter.RELEASE, effectiveRequest, sim.month(),
                members, sim.skills(), flags, sim.lastEvents(), sim.scheduled(), sim.pendingTemplate(), facts, sim.death(), calibration,
                sim.naming() == null ? null : sim.naming().reconcile(profile.name(), sex, members),sim.focus());
        var stats = state.stats();
        if (changes.family() != null && changes.family().members() != null && (changes.stats() == null || changes.stats().relationships() == null)) {
            int bonds = (int) members.stream().mapToInt(LocalSimulation.Person::closeness).average().orElse(0);
            stats = new LifeState.Stats(stats.health(), stats.happiness(), stats.intelligence(), bonds, stats.appearance(), stats.aura());
        }
        String birthText = "Место рождения: " + place.country() + (place.city() == null ? "" : ", " + place.city())
                + ". Семья: " + families.familyLabel(members) + ". Братьев и сестёр: "
                + members.stream().filter(p -> Set.of("brother", "sister").contains(p.role())).count() + ".";
        var history = state.history().stream().map(e -> e.id().equals("birth") ? new LifeState.HistoryEvent("birth", 0, "Рождение", birthText) : e).toList();
        return new Result(new CharacterCustomization(updatedProfile, updatedDetails, originalRequest), updatedSim,
                new LifeState(state.age(), state.status(), stats, state.pendingDecision(), history));
    }

    private CharacterProfile.Birthplace birthplace(CharacterProfile.Birthplace old, CharacterChanges.Birthplace edits) {
        if (edits == null) return old;
        String iso2 = edits.countryCode() == null ? old.countryCode() : edits.countryCode();
        String iso3;
        try { iso3 = countries.iso3(iso2); }
        catch (NoSuchElementException ex) { throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_COUNTRY", "Choose one of the 36 supported countries."); }
        boolean moved = !iso2.equals(old.countryCode());
        return new CharacterProfile.Birthplace(iso2, countries.get(iso3).name(),
                field(edits.region(), moved ? null : old.region()), field(edits.city(), moved ? null : old.city()));
    }

    private BirthFacts provenance(BirthFacts facts, BirthFacts old, CharacterChanges.Family edits, boolean countryChanged) {
        boolean has = edits != null;
        return new BirthFacts(facts.countryIso3(), facts.sex(), facts.worldBirthProbability(), facts.selectionProbability(), facts.gdpPerCapita(), facts.lifeExpectancy(), facts.underFiveMortality(),
                facts.currency(), facts.countryReligions(), facts.countryLanguages(),
                description(facts.wealth(), old == null ? null : old.wealth(), countryChanged || has && edits.incomeQuintile() != null),
                description(facts.housing(), old == null ? null : old.housing(), countryChanged || has && edits.housing() != null),
                description(facts.familyRelationships(), old == null ? null : old.familyRelationships(), has && (edits.relationships() != null || edits.members() != null)),
                description(facts.familyFaith(), old == null ? null : old.familyFaith(), countryChanged || has && edits.religion() != null),
                description(facts.familyLanguage(), old == null ? null : old.familyLanguage(), countryChanged || has && edits.language() != null), facts.sources());
    }
    private BirthFacts.Description description(BirthFacts.Description value, BirthFacts.Description old, boolean edited) {
        return edited ? new BirthFacts.Description(value.value(), "GAME_ASSUMPTION", "user-customization",
                "Задано или сохранено пользователем при настройке рождения; не результат статистической выборки для этой страны.") : old == null ? value : old;
    }
    private String field(String value, String fallback) { return value == null ? fallback : value.isEmpty() ? null : value; }
    private ApiFailure conflict(String code, String text) { return new ApiFailure(HttpStatus.CONFLICT, code, text); }
    record Result(CharacterCustomization customization, LocalSimulation simulation, LifeState state) { }
}
