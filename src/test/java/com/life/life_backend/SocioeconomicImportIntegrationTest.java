package com.life.life_backend;

import com.life.life_backend.statistics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {"spring.datasource.url=${LIFE_TEST_DB_URL}",
        "spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}", "spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"})
class SocioeconomicImportIntegrationTest {
    @Autowired SocioeconomicImporter importer;
    @Autowired SocioeconomicBundle bundles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void importsFamilyHousingAndVerifiesIdempotencyWithoutChangingSaves() throws Exception {
        String before = savedLives();
        var data = bundles.load(SocioeconomicBundle.FAMILY);
        importer.importBundled(data.id());
        var repeated = importer.importBundled(data.id());
        assertThat(repeated.newlyImported()).isFalse();
        assertThat(repeated.observations()).isEqualTo(11427);
        assertThat(jdbc.queryForObject("select count(distinct country_iso3) from socioeconomic_observation where release_id=?",
                Integer.class, data.id())).isEqualTo(36);
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_observation where release_id=? and quality='REJECTED' and value is null",
                Integer.class, data.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_coverage where release_id=? and indicator in ('EG.ELC.ACCS.ZS','SH.H2O.BASW.ZS','SH.STA.BASS.ZS') and valid_count>0",
                Integer.class, data.id())).isEqualTo(108);
        assertThat(savedLives()).isEqualTo(before);
    }

    @Test void detectsChangedValuesAndMissingRowsInsteadOfSilentlyRepairingRelease() throws Exception {
        importer.importBundled(SocioeconomicBundle.FAMILY);
        // Every corruption is rolled back in the isolated test database.
        for (String mutation : new String[]{
                "update socioeconomic_observation set value=value+0.01 where release_id=? and quality='VALID'",
                "delete from socioeconomic_observation where release_id=? and country_iso3='RUS'",
                "update socioeconomic_coverage set valid_count=valid_count+1 where release_id=? and valid_count>0",
                "update socioeconomic_release set manifest=jsonb_set(manifest,'{rowCount}','0') where id=?"}) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.update(mutation, SocioeconomicBundle.FAMILY);
                assertThatThrownBy(() -> importer.importBundled(SocioeconomicBundle.FAMILY)).isInstanceOf(IllegalStateException.class);
                tx.setRollbackOnly();
            });
        }
        importer.importBundled(SocioeconomicBundle.FAMILY);
    }

    @Test void importsWorkIncomeWithExplicitCurrencyQualityAndCoverage() throws Exception {
        String before = savedLives();
        importer.importBundled(SocioeconomicBundle.FAMILY);
        var report = importer.importBundled(SocioeconomicBundle.WORK);
        assertThat(report.observations()).isEqualTo(156402);
        assertThat(importer.importBundled(SocioeconomicBundle.WORK).newlyImported()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_observation where release_id=?",
                Integer.class, SocioeconomicBundle.FAMILY)).isEqualTo(11427);
        assertThat(jdbc.queryForObject("""
                select count(*) from socioeconomic_observation where release_id=? and indicator like 'EAR_%'
                  and not (dimensions @> '{"classif1":"CUR_TYPE_LCU"}'::jsonb
                       or dimensions @> '{"classif2":"CUR_TYPE_LCU"}'::jsonb)
                """, Integer.class, SocioeconomicBundle.WORK)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from socioeconomic_observation where release_id=?
                  and provenance->>'obs_status'='U' and (quality='VALID' or value is not null)
                """, Integer.class, SocioeconomicBundle.WORK)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_observation where release_id=? and quality='REJECTED'",
                Integer.class, SocioeconomicBundle.WORK)).isEqualTo(1799);
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_coverage where release_id=? and indicator='EAR_EMTA_SEX_CUR_NB' and valid_count>0",
                Integer.class, SocioeconomicBundle.WORK)).isEqualTo(34);
        assertThat(jdbc.queryForObject("select count(*) from socioeconomic_coverage where release_id=? and indicator='EAR_EMTM_SEX_CUR_NB' and valid_count>0",
                Integer.class, SocioeconomicBundle.WORK)).isEqualTo(25);
        assertThat(savedLives()).isEqualTo(before);
    }

    private String savedLives() {
        return jdbc.queryForObject("select md5(coalesce(string_agg(md5(row_to_json(s)::text), ',' order by id),'')) from life_session s", String.class);
    }

    @Test void importsChinaModelledReleaseWithoutChangingPreviousReleasesOrSaves() throws Exception {
        String before = savedLives();
        var first = importer.importBundled(SocioeconomicBundle.CHINA_OCCUPATIONS);
        assertThat(first.observations()).isEqualTo(702);
        assertThat(importer.importBundled(SocioeconomicBundle.CHINA_OCCUPATIONS).newlyImported()).isFalse();
        var data = bundles.load(SocioeconomicBundle.CHINA_OCCUPATIONS);
        assertThat(data.observations()).allMatch(row -> row.country().equals("CHN") && row.quality().equals("VALID"));
        assertThat(data.coverage().getFirst().latestYear()).isEqualTo(2025);
        assertThat(savedLives()).isEqualTo(before);
    }
}
