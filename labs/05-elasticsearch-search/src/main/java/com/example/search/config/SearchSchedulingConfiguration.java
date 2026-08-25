package com.example.search.config;

import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.maintenance.SearchRebuildRecovery;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SearchProperties.class)
public class SearchSchedulingConfiguration {
    @Bean
    public AtomicBoolean searchStartupReady() {
        return new AtomicBoolean(false);
    }

    @Bean
    public ApplicationRunner searchStartupRunner(SearchRebuildRecovery recovery,
                                                 SearchIndexBootstrap bootstrap,
                                                 AtomicBoolean searchStartupReady) {
        return arguments -> {
            recovery.recoverInterruptedCutover();
            bootstrap.ensureInitialized();
            searchStartupReady.set(true);
        };
    }
}
