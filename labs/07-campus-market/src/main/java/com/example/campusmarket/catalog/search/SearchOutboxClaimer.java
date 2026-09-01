package com.example.campusmarket.catalog.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 独立代理 bean，确保 FOR UPDATE claim 不被 self-invocation 绕过。 */
@Service
public class SearchOutboxClaimer {
    private final JdbcTemplate jdbc;

    public SearchOutboxClaimer(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空"); }

    @Transactional
    public List<SearchOutboxDispatcher.Claim> claim(String owner, int limit, Duration lease) {
        if (owner == null || owner.isBlank() || limit <= 0 || limit > 1000 || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("领取参数无效");
        }
        long micros = Math.addExact(Math.multiplyExact(lease.getSeconds(), 1_000_000L), lease.getNano() / 1_000L);
        List<SearchOutboxDispatcher.Claim> result = new ArrayList<>();
        jdbc.query("SELECT id,listing_id,aggregate_version,event_type,payload,created_at,attempt_count FROM search_outbox WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6)) OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)) ORDER BY sequence_no LIMIT ? FOR UPDATE SKIP LOCKED", rs -> {
            String id = rs.getString("id");
            String token = UUID.randomUUID().toString();
            int attempts = rs.getInt("attempt_count") + 1;
            int changed = jdbc.update("UPDATE search_outbox SET status='PUBLISHING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),attempt_count=? WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6)) OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))", owner, token, micros, attempts, id);
            if (changed == 1) result.add(new SearchOutboxDispatcher.Claim(id, UUID.fromString(rs.getString("listing_id")), rs.getLong("aggregate_version"), rs.getString("event_type"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant(), owner, token, attempts));
        }, limit);
        return List.copyOf(result);
    }
}
