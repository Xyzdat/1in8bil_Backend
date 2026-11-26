package com.life.life_backend.statistics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "life.statistics.socioeconomic-import")
class SocioeconomicImportCommand implements ApplicationRunner {
    private final SocioeconomicImporter importer;
    private final ConfigurableApplicationContext context;
    private final String stage;

    SocioeconomicImportCommand(SocioeconomicImporter importer, ConfigurableApplicationContext context,
                               @Value("${life.statistics.socioeconomic-import}") String stage) {
        this.importer = importer;
        this.context = context;
        this.stage = stage;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String release = switch (stage) {
            case "family-housing" -> SocioeconomicBundle.FAMILY;
            case "work-income" -> SocioeconomicBundle.WORK;
            case "china-occupations" -> SocioeconomicBundle.CHINA_OCCUPATIONS;
            default -> throw new IllegalArgumentException("Use family-housing, work-income or china-occupations");
        };
        System.out.println("Verified socioeconomic import: " + importer.importBundled(release));
        context.close();
    }
}
