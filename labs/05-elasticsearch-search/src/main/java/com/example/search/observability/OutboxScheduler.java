package com.example.search.observability;

import com.example.search.application.sync.OutboxDispatcher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "search.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private final OutboxDispatcher dispatcher;
    private final AtomicBoolean searchStartupReady;

    public OutboxScheduler(OutboxDispatcher dispatcher, AtomicBoolean searchStartupReady) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher is required");
        this.searchStartupReady = Objects.requireNonNull(searchStartupReady, "startup gate is required");
    }

    @Scheduled(fixedDelayString = "${search.dispatch-delay:1s}")
    public void dispatch() {
        if (searchStartupReady.get()) {
            dispatcher.dispatchOnce();
        }
    }
}
