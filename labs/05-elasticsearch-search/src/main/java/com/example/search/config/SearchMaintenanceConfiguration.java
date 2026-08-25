package com.example.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, named executor for asynchronous rebuild requests. */
@Configuration(proxyBeanMethods = false)
public class SearchMaintenanceConfiguration {
    @Bean(name = "searchRebuildExecutor", destroyMethod = "shutdown")
    ExecutorService searchRebuildExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "searchRebuildExecutor-1");
            thread.setDaemon(true);
            return thread;
        });
    }
}
