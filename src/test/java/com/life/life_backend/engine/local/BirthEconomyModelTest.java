package com.life.life_backend.engine.local;

import com.life.life_backend.statistics.SocioeconomicReference.Observation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BirthEconomyModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> quintiles = List.of("SI.DST.FRST.20","SI.DST.02ND.20","SI.DST.03RD.20","SI.DST.04TH.20","SI.DST.05TH.20");
    private Observation income(int quintile,int year,double value) {
        return new Observation("income-"+quintile+"-"+year,quintiles.get(quintile-1),year,json.createObjectNode(),value,json.createObjectNode(),"wdi-income-q"+quintile);
    }
    private List<Observation> incomes(int year,double... values) {
        var rows = new ArrayList<Observation>(); for(int i=0;i<values.length;i++) rows.add(income(i+1,year,values[i])); return rows;
    }
    private Observation occupation(String source,int year,String sex,String group,double value,String note) {
        return new Observation(source+year+sex+group,"EMP_TEMP_SEX_OCU_NB",year,
                json.valueToTree(Map.of("source",source,"sex",sex,"classif1","OCU_ISCO08_"+group)),value,
                json.valueToTree(Map.of("note_classif",note)),"ilo-occupations");
    }

    @Test void quintileSharesCalibrateAmountsWithoutBecomingSelectionProbabilities() {
        var lowInequality = incomes(2023,10,15,20,25,30);
        var highInequality = incomes(2023,5,10,15,20,50);
        double mean = 0;
        for(int q=1;q<=5;q++) mean += FamilyResourcesModel.sample(highInequality,q).relativeToCountryMean()/5;
        assertThat(mean).isCloseTo(1,within(1e-12));
        assertThat(FamilyResourcesModel.sample(lowInequality,1).relativeToCountryMean()).isEqualTo(.5);
        assertThat(FamilyResourcesModel.sample(highInequality,1).relativeToCountryMean()).isEqualTo(.25);
        assertThat(FamilyResourcesModel.sample(highInequality,5).relativeToCountryMean()).isEqualTo(2.5);
        assertThat(FamilyResourcesModel.label(FamilyResourcesModel.sample(highInequality,1))).contains("0,25", "по стране");
    }

    @Test void usesOneCompleteYearAndRejectsInvalidOrFutureIncomeDistribution() {
        var rows = new ArrayList<>(incomes(2022,10,15,20,25,30));
        rows.addAll(incomes(2023,30,15,20,25,10)); // Nonmonotone quintiles cannot describe ordered incomes.
        rows.add(income(1,2024,5)); rows.addAll(incomes(2025,5,10,15,20,50));
        var selected = FamilyResourcesModel.distribution(rows,2024);
        assertThat(selected).hasSize(5).allMatch(r -> r.year()==2022);
        var missing = FamilyResourcesModel.sample(FamilyResourcesModel.distribution(rows,2021),3);
        assertThat(missing.relativeToCountryMean()).isNull(); assertThat(missing.basis()).isEqualTo("GAME_ASSUMPTION");
    }

    @Test void occupationProbabilitiesFollowCountryCountsAndKeepUnclassifiedRemainder() {
        var rows = List.of(occupation("A",2024,"SEX_F","TOTAL",100,""),occupation("A",2024,"SEX_F","2",20,""),
                occupation("A",2024,"SEX_F","6",70,""),occupation("A",2024,"SEX_M","2",9000,""),
                occupation("B",2024,"SEX_F","9",9000,""));
        var distribution = ParentOccupationModel.distribution(rows,2024,"SEX_F");
        assertThat(distribution.weights()).containsEntry("OCU_ISCO08_2",.2).containsEntry("OCU_ISCO08_6",.7);
        assertThat(distribution.weights().get("OCU_ISCO08_X")).isCloseTo(.1,within(1e-12));
        var counts = new HashMap<String,Integer>();
        for(int seed=1;seed<=5000;seed++) counts.merge(ParentOccupationModel.sample(distribution,new SplittableRandom(seed)).code(),1,Integer::sum);
        assertThat(counts.get("OCU_ISCO08_2")/5000d).isCloseTo(.2,within(.025));
        assertThat(counts.get("OCU_ISCO08_6")/5000d).isCloseTo(.7,within(.025));
        assertThat(counts).doesNotContainKey("OCU_ISCO08_TOTAL");
    }

    @Test void mergedClassificationsAreNotMisrepresentedAndInconsistentTotalsAreRejected() {
        var rows = List.of(occupation("A",2024,"SEX_F","TOTAL",100,""),occupation("A",2024,"SEX_F","2",60,"C4:1002"),
                occupation("A",2024,"SEX_F","5",40,""),occupation("A",2025,"SEX_F","TOTAL",100,""),occupation("A",2025,"SEX_F","2",200,""));
        var distribution = ParentOccupationModel.distribution(rows,2025,"SEX_F");
        assertThat(distribution.weights()).containsOnlyKeys("OCU_ISCO08_5","OCU_ISCO08_X");
        assertThat(distribution.weights().get("OCU_ISCO08_X")).isEqualTo(.6);
        assertThat(distribution.evidence()).allMatch(e -> e.year()==2024);
        var inconsistentUnknown = List.of(occupation("A",2024,"SEX_F","TOTAL",100,""),occupation("A",2024,"SEX_F","2",80,""),
                occupation("A",2024,"SEX_F","X",40,""));
        assertThat(ParentOccupationModel.distribution(inconsistentUnknown,2024,"SEX_F").weights()).isEmpty();
    }

    @Test void usesBothSexesOnlyAsExplicitFallbackAndNeverInventsMissingCountryJobs() {
        var total = List.of(occupation("A",2023,"SEX_T","TOTAL",100,""),occupation("A",2023,"SEX_T","6",100,""));
        var fallback = ParentOccupationModel.sample(ParentOccupationModel.distribution(total,2024,"SEX_F"),new SplittableRandom(1));
        assertThat(fallback.sampledSex()).isEqualTo("SEX_T"); assertThat(fallback.note()).contains("обоих полов");
        var absent = ParentOccupationModel.sample(ParentOccupationModel.distribution(total,2022,"SEX_M"),new SplittableRandom(1));
        assertThat(absent.basis()).isEqualTo("MISSING"); assertThat(absent.code()).isEqualTo("UNSPECIFIED"); assertThat(absent.evidence()).isEmpty();
    }
}
