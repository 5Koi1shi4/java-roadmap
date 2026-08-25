package com.example.search.integration;

import org.springframework.test.context.TestPropertySource;

/** Enables the production scheduler only for tests that exercise automatic recovery. */
@TestPropertySource(properties = {"search.startup.enabled=true", "search.scheduling.enabled=true"})
abstract class SharedScheduledSearchContainers extends SharedSearchContainers {
}
