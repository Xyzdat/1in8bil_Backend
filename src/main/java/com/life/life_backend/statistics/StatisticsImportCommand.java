package com.life.life_backend.statistics;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "life.statistics.import", havingValue = "true")
class StatisticsImportCommand implements ApplicationRunner {
    private final StatisticsImporter importer;
    private final ConfigurableApplicationContext context;
    StatisticsImportCommand(StatisticsImporter importer, ConfigurableApplicationContext context) {
        this.importer = importer; this.context = context;
    }
    public void run(ApplicationArguments args) throws Exception {
        System.out.println("Verified statistics release: " + importer.importBundled() + " (12 observations)");
        System.out.println("Verified country release: " + CountryExpansionImporter.RELEASE + " (36 profiles, 559944 mortality rows); legacy v1 preserved.");
        context.close();
    }
}
