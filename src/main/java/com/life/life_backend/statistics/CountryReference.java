package com.life.life_backend.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;

@Repository
public class CountryReference {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    private final CountryCatalog catalog;
    private volatile List<Profile> cached;
    public CountryReference(JdbcTemplate jdbc,ObjectMapper json,CountryCatalog catalog){this.jdbc=jdbc;this.json=json;this.catalog=catalog;}
    public List<Profile> all(){
        if(cached!=null)return cached;
        var rows=jdbc.queryForList("select profile::text from country_birth_reference where release_id=? order by iso3",String.class,CountryExpansionImporter.RELEASE);
        var profiles=rows.stream().map(s->json.readValue(s,Profile.class)).toList();
        var codes=profiles.stream().map(Profile::iso3).collect(java.util.stream.Collectors.toSet());
        if(profiles.size()!=catalog.codes().size()||!codes.equals(catalog.codes()))
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE,"STATISTICS_NOT_READY","Import expanded birth statistics.");
        cached=profiles;
        return profiles;
    }
    public Profile get(String iso){return all().stream().filter(p->p.iso3().equals(iso)).findFirst().orElseThrow();}
    public record Language(String code,double percent,String status){}
    public record Religion(String code,double percent){}
    public record Profile(String iso3,String iso2,String name,double births,double worldBirths,double lifeExpectancy,
            double underFiveProbability,double gdpPerCapitaUsd,String currency,List<Language> languages,List<Religion> religions,int year,int gdpYear,
            double maleFemaleRatio,double urbanShare,int sexRatioYear,int urbanYear){}
}
