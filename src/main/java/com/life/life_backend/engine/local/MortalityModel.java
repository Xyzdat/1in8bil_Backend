package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.ExpandedStatisticsImporter;
import com.life.life_backend.statistics.CountryExpansionImporter;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MortalityModel {
    private final JdbcTemplate jdbc;
    private final Map<String,Map<Integer,Rate>> tables=new ConcurrentHashMap<>();
    public MortalityModel(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public double probability(String iso,String sex,int year,int age,double years,double multiplier){
        return probability(CountryExpansionImporter.RELEASE,iso,sex,year,age,years,multiplier);
    }
    private double probability(String release,String iso,String sex,int year,int age,double years,double multiplier){
        var table=tables.computeIfAbsent(release+":"+iso+sex,k->{
            var loaded=new HashMap<Integer,Rate>();
            jdbc.query("select year,age,qx,mx from mortality_rate where release_id=? and iso3=? and sex=?",
                    rs->{loaded.put(rs.getInt(1)*101+rs.getInt(2),new Rate(rs.getDouble(3),rs.getDouble(4)));},release,iso,sex);
            if(loaded.size()!=77*101)throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE,"STATISTICS_NOT_READY","Mortality table unavailable.");
            return Map.copyOf(loaded);
        });
        // Beyond the projection horizon use the 2100 schedule; 100+ is an open interval, not forced death.
        var rate=table.get(Math.clamp(year,2024,2100)*101+Math.min(age,100));
        double hazard=age>=100?rate.mx():-Math.log1p(-rate.qx());
        return -Math.expm1(-hazard*years*multiplier);
    }
    public boolean dies(LocalSimulation s,String iso,String sex,int birthMonth,int health,String personId){
        if (health <= 0) return true;
        int age=Math.max(0,(s.month()-birthMonth)/12);
        int year=s.request().birthYear()+s.month()/12;
        // Explicit game modifier, not an epidemiological estimate. Neutral at health 70; aura never affects death risk.
        double multiplier=Math.exp((70-Math.clamp(health,0,100))/100.0);
        String release = LocalSimulation.STATISTICS.equals(s.statisticsRelease()) ? ExpandedStatisticsImporter.RELEASE : s.statisticsRelease();
        double p=probability(release,iso,sex,year,age,2.0/12,multiplier);
        long salt=0xD1B54A32D192ED03L ^ ((long)personId.hashCode()<<32);
        return new SplittableRandom(s.seed()^salt^(0x9E3779B97F4A7C15L*s.month())).nextDouble()<p;
    }
    record Rate(double qx,double mx){}
}
