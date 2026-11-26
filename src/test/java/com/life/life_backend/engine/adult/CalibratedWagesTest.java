package com.life.life_backend.engine.adult;

import com.life.life_backend.economy.*;
import com.life.life_backend.engine.LifeState;
import com.life.life_backend.engine.local.LocalSimulation;
import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static com.life.life_backend.engine.adult.AdultState.*;

class CalibratedWagesTest {
    private final ObjectMapper json = new ObjectMapper();

    private SocioeconomicReference.Observation observation(String id,String indicator,String source,int year,String group,double value) {
        return new SocioeconomicReference.Observation(id,indicator,year,
                json.valueToTree(Map.of("source",source,"sex","SEX_T","classif1",group,"classif2","CUR_TYPE_LCU")),value,json.createObjectNode(),"ilo-test");
    }

    @Test void ratiosNeverMixSurveysYearsOrCurrencyAmounts() {
        var data = List.of(
                observation("a","EAR_EMTA_SEX_OCU_CUR_NB","A",2024,"OCU_ISCO08_TOTAL",100_000),
                observation("b","EAR_EMTA_SEX_OCU_CUR_NB","A",2024,"OCU_ISCO08_2",150_000),
                observation("c","EAR_EMTA_SEX_OCU_CUR_NB","B",2025,"OCU_ISCO08_2",9_000_000),
                observation("d","EAR_EMTA_SEX_OCU_CUR_NB","A",2023,"OCU_ISCO08_7",5_000_000));
        var calibrated = LabourCalibrationFactory.calibrate(data);
        assertThat(calibrated.occupationPayRatios()).containsExactlyEntriesOf(Map.of("2",1.5));
        assertThat(calibrated.evidence()).extracting(SocioeconomicCalibration.Evidence::rowKey).containsExactlyInAnyOrder("a","b");
        var frozen = new SocioeconomicCalibration(SocioeconomicCalibration.VERSION,"family","work",null,Map.of("IN",calibrated));
        assertThat(CalibratedWages.monthly(1_000_000,frozen,"IN","Инженер")).isEqualTo(1_500_000);
        assertThat(CalibratedWages.monthly(1_000_000,frozen,"JP","Инженер")).isEqualTo(1_000_000);
        assertThat(CalibratedWages.monthly(1_000_000,null,"IN","Инженер")).isEqualTo(1_000_000);
    }

    @Test void actualJobOfferUsesCalibrationAndPayrollPostsItOnlyOnceAfterRestore() throws Exception {
        var ratios = new SocioeconomicCalibration.Labour(Map.of("TYPICAL",1.5),List.of(),List.of());
        var frozen = new SocioeconomicCalibration(SocioeconomicCalibration.VERSION,"family","work",null,Map.of("RU",ratios));
        var calibrated = world(frozen); var legacy = world(null);
        var career = new CareerSystem();
        for (int i = 0; i < 50 && !legacy.employed(); i++) {
            career.act("apply_job",legacy,progress()); career.act("apply_job",calibrated,progress());
        }
        assertThat(legacy.employed()).isTrue(); assertThat(calibrated.employed()).isTrue();
        assertThat(calibrated.career.occupation()).isEqualTo(legacy.career.occupation());
        assertThat(calibrated.career.monthlyPay()).isEqualTo(Math.round(legacy.career.monthlyPay()*1.5));
        var restoredSim = json.readValue(json.writeValueAsString(calibrated.sim),LocalSimulation.class);
        var restored = new AdultWorld(calibrated.state,restoredSim,calibrated.budget,
                json.readValue(json.writeValueAsString(calibrated.snapshot()),AdultState.class),calibrated.location,calibrated.countries,1);
        var economy = new AdultEconomy(mock(SpendingService.class));
        economy.settle(calibrated,362); economy.settle(restored,362);
        assertThat(restored.budget).isEqualTo(calibrated.budget);
        long pay = calibrated.career.monthlyPay();
        assertThat(calibrated.budget.entries().stream().filter(e -> e.id().startsWith("salary-")).mapToLong(LocalBudget.Entry::income).sum()).isEqualTo(pay*2);
        assertThat(calibrated.budget.entries()).noneMatch(e -> e.id().startsWith("budget-"));
        var settled = calibrated.budget; economy.settle(calibrated,362);
        assertThat(calibrated.budget).isEqualTo(settled);
        // Existing employment retains its stored amount even if its owner moves country.
        var moved = new AdultWorld(restored.state,restored.sim,restored.budget,restored.snapshot(),new FinanceView.Location("JP","Япония",null),restored.countries,1);
        assertThat(moved.career.monthlyPay()).isEqualTo(pay);
    }

    private AdultWorld world(SocioeconomicCalibration calibration) throws Exception {
        var sim = new LocalSimulation(47,LocalSimulation.VERSION,LocalSimulation.STATISTICS,new LocalSimulation.BirthRequest("RUS",2026),360,
                List.of(),Map.of(),Set.of(),Map.of(),List.of(),null,null,null,calibration);
        var state = new LifeState(30,LifeState.Status.ACTIVE,new LifeState.Stats(100,65,65,60,50,0),null,List.of());
        return new AdultWorld(state,sim,new LocalBudget(360,10_000_000,List.of(),List.of()),null,
                new FinanceView.Location("RU","Россия",null),new CountryCatalog(json),1);
    }
    private StoryProgress progress() {
        return new StoryProgress(new Story("job","job","CHOICE","ACTIVE",360,360,null,List.of(),Map.of(),List.of()));
    }
}
