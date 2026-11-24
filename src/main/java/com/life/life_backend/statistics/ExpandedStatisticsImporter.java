package com.life.life_backend.statistics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ExpandedStatisticsImporter {
    public static final String RELEASE = "birth-context-2026-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ExpandedStatisticsImporter(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private byte[] resource(String name) throws Exception {
        try(var stream=new ClassPathResource("statistics/expanded/"+name).getInputStream()){return stream.readAllBytes();}
    }
    @Transactional
    public void importBundled() throws Exception {
        var manifest=json.readTree(resource("manifest.json"));
        if(!manifest.path("id").asString().equals(RELEASE))throw new IllegalStateException("Unknown reference release");
        var countries=verified("countries.json",manifest);
        var mortality=verified("mortality.json",manifest);
        if(countries.size()!=3 || mortality.size()!=46662)throw new IllegalStateException("Incomplete reference coverage");
        jdbc.queryForObject("select pg_advisory_xact_lock(748232)",Object.class);
        var existing=jdbc.queryForList("select manifest::text from reference_release where id=?",String.class,RELEASE);
        if(!existing.isEmpty()&&!json.readTree(existing.getFirst()).equals(manifest))throw new IllegalStateException("Immutable reference release changed");
        jdbc.update("insert into reference_release values(?,?::jsonb, current_timestamp) on conflict do nothing",RELEASE,manifest.toString());
        var seen=new HashSet<String>();
        for(var c:countries){
            String iso=c.path("iso3").asString();
            if(!Set.of("RUS","JPN","IND").contains(iso)||!seen.add(iso)||c.path("gdpPerCapitaUsd").asDouble()<=0
                    || c.path("underFiveProbability").asDouble(-1)<0 || c.path("underFiveProbability").asDouble()>1)
                throw new IllegalStateException("Invalid country profile");
            jdbc.update("insert into country_birth_reference values(?,?,?::jsonb) on conflict do nothing",RELEASE,iso,c.toString());
        }
        var batch=new ArrayList<Object[]>(); seen.clear();
        for(var r:mortality){
            String iso=r.get(0).asString(), sex=r.get(1).asString(); int year=r.get(2).asInt(),age=r.get(3).asInt();
            double q=r.get(4).asDouble(),mx=r.get(5).asDouble();
            if(!Set.of("RUS","JPN","IND").contains(iso)||!Set.of("MALE","FEMALE").contains(sex)||year<2024||year>2100||age<0||age>100
                    ||q<0||q>1||mx<0||!Double.isFinite(q)||!Double.isFinite(mx)||!seen.add(iso+sex+year+":"+age))
                throw new IllegalStateException("Invalid mortality row");
            batch.add(new Object[]{RELEASE,iso,sex,year,age,q,mx});
        }
        jdbc.batchUpdate("insert into mortality_rate values(?,?,?,?,?,?,?) on conflict do nothing",batch);
        if(jdbc.queryForObject("select count(*) from mortality_rate where release_id=?",Integer.class,RELEASE)!=46662)
            throw new IllegalStateException("Mortality import count mismatch");
    }
    private JsonNode verified(String file,JsonNode manifest)throws Exception{
        byte[] bytes=resource(file);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!hash.equals(manifest.path("hashes").path(file).asString()))throw new IllegalStateException("Reference checksum mismatch: "+file);
        return json.readTree(bytes);
    }
}
