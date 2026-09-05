package com.example.campusmarket.dispute.infrastructure;

import com.example.campusmarket.dispute.application.EvidenceCaseAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public final class JdbcEvidenceCaseAccess implements EvidenceCaseAccess {
    private final JdbcTemplate jdbc;
    public JdbcEvidenceCaseAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean canAttach(String caseType, UUID caseId, UUID actorId) {
        return canRead(caseType, caseId, actorId);
    }

    @Override public boolean canRead(String caseType, UUID caseId, UUID actorId) {
        if (!"DISPUTE".equals(caseType) || caseId == null || actorId == null) return false;
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case c JOIN trade_order o ON o.id=c.order_id WHERE c.id=? AND (o.buyer_id=? OR o.seller_id=? OR c.assigned_admin_id=?)",
            Integer.class, caseId.toString(), actorId.toString(), actorId.toString(), actorId.toString());
        return found != null && found == 1;
    }
}
