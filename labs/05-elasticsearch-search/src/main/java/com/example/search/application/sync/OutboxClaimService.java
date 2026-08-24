package com.example.search.application.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {
    private final SearchCoordinationRepository coordinationRepository;
    private final SearchOutboxRepository outboxRepository;

    public OutboxClaimService(SearchCoordinationRepository coordinationRepository,
                              SearchOutboxRepository outboxRepository) {
        this.coordinationRepository = coordinationRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public List<ClaimedOutboxEvent> claim(String owner, int limit) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1 to 128 characters");
        }
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        if (coordinationRepository.lockSharedAndReadDispatcherPaused()) {
            return List.of();
        }
        return outboxRepository.claim(owner, limit);
    }

    @Transactional
    public boolean complete(UUID eventId, UUID token) {
        return outboxRepository.complete(eventId, token);
    }

    @Transactional
    public boolean reschedule(UUID eventId, UUID token, Duration delay, String reason) {
        return outboxRepository.reschedule(eventId, token, delay, reason);
    }

    @Transactional
    public boolean fail(UUID eventId, UUID token, String reason) {
        return outboxRepository.fail(eventId, token, reason);
    }
}
