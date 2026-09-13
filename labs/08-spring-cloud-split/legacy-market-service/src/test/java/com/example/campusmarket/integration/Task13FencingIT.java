package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.messaging.OutboxRepository;
import com.example.campusmarket.observability.CampusMetrics;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import com.example.campusmarket.warranty.application.WarrantyDeadlineScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 13 的轻量 MySQL 边界证据；执行 verify 时只启动 MySQL。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(Task13FencingIT.PasswordEncoderTestConfiguration.class)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "campus.market.order.deadline.enabled=false",
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false",
    "campus.market.payment.reconciliation.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false",
    "campus.market.metrics.refresh-ms=3600000"
})
class Task13FencingIT extends Task11MySqlContainers {

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordEncoderTestConfiguration {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        WarrantyDeadlineScheduler warrantyDeadlineScheduler(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CampusMetrics metrics) {
            return new WarrantyDeadlineScheduler(jdbc, transactionManager, metrics);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired StorageCleanupScheduler cleanup;
    @Autowired WarrantyDeadlineScheduler warrantyDeadlines;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM manual_failure WHERE source_type='OUTBOX' AND source_id IN (SELECT event_id FROM integration_outbox WHERE event_type='TASK13_FENCE')");
        jdbc.update("DELETE FROM integration_outbox WHERE event_type='TASK13_FENCE'");
        jdbc.update("DELETE FROM storage_cleanup_task WHERE cleanup_business_key LIKE 'task13-fence:%'");
        jdbc.update("DELETE FROM warranty_deadline_claim WHERE warranty_case_id IN (SELECT id FROM warranty_case WHERE idempotency_key LIKE 'task13-fence-%')");
        jdbc.update("DELETE FROM warranty_case WHERE idempotency_key LIKE 'task13-fence-%'");
        jdbc.update("DELETE FROM trade_order WHERE listing_id IN (SELECT id FROM listing WHERE title LIKE 'task13-fence-%')");
        jdbc.update("DELETE FROM listing WHERE title LIKE 'task13-fence-%'");
    }

    @Test
    void expiredOutboxFailureDoesNotChangeSourceOrCreateManualCopy() {
        UUID eventId = insertOutbox("DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)",
            "task13-old-owner", "task13-old-token");

        assertThat(outbox.fail(eventId, "task13-old-owner", "task13-old-token", "PERMANENT")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class, eventId.toString()))
            .isEqualTo("PUBLISHING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            Integer.class, eventId.toString())).isZero();
    }

    @Test
    void liveOutboxFailureAtomicallyCreatesOneManualCopy() {
        UUID eventId = insertOutbox("DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)",
            "task13-live-owner", "task13-live-token");

        assertThat(outbox.fail(eventId, "task13-live-owner", "task13-live-token", "EXHAUSTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM integration_outbox WHERE event_id=?", String.class, eventId.toString()))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            Integer.class, eventId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failure_class FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            String.class, eventId.toString())).isEqualTo("EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.task')) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            String.class, eventId.toString())).isEqualTo("task13");
        assertThat(outbox.fail(eventId, "task13-live-owner", "task13-live-token", "EXHAUSTED")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='OUTBOX' AND source_id=?",
            Integer.class, eventId.toString())).isEqualTo(1);
    }

    @Test
    void expiredCleanupLeaseCannotCompleteOrReleaseTask() throws Exception {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO storage_cleanup_task(id,cleanup_business_key,object_key,owner_id,claim_token,lease_until,attempt_count,status,run_after,created_at,updated_at) VALUES (?,?,?,'task13-old-owner','task13-old-token',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),1,'PROCESSING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id, "task13-fence:" + id, "task13-object/" + id);
        Object task = cleanupTask(id, "task13-object/" + id, "task13-old-owner", "task13-old-token");

        assertThat(invokeCleanup("complete", task)).isEqualTo(false);
        assertThat(invokeCleanup("release", task, "TRANSIENT")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE id=?", String.class, id)).isEqualTo("PROCESSING");
    }

    @Test
    void terminalWarrantyAdminSlaDoesNotUpdateOrCountAndOpenCountsOnce() {
        UUID terminal = insertWarrantyCase("RESOLVED");
        Timestamp before = jdbc.queryForObject("SELECT updated_at FROM warranty_case WHERE id=?", Timestamp.class, terminal.toString());
        insertAdminClaim(terminal);
        assertThat(warrantyDeadlines.runOne(terminal)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM warranty_case WHERE id=?", Timestamp.class, terminal.toString()))
            .isEqualTo(before);
        assertThat(meters.find("campus.market.admin.sla.timeout.total").tag("result", "TIMEOUT").counter()).isNull();

        UUID open = insertWarrantyCase("UNDER_REVIEW");
        insertAdminClaim(open);
        assertThat(warrantyDeadlines.runOne(open)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM warranty_case WHERE id=?", String.class, open.toString())).isEqualTo("ESCALATED");
        assertThat(meters.get("campus.market.admin.sla.timeout.total").tag("result", "TIMEOUT").counter().count()).isEqualTo(1.0);
        assertThat(warrantyDeadlines.runOne(open)).isZero();
        assertThat(meters.get("campus.market.admin.sla.timeout.total").tag("result", "TIMEOUT").counter().count()).isEqualTo(1.0);
    }

    private UUID insertOutbox(String leaseExpression, String owner, String token) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at) VALUES (?,?, 'TASK13_FENCE', ?,1,1,CAST(? AS JSON),'PUBLISHING',?,?," + leaseExpression + ",1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), eventId.toString(), UUID.randomUUID().toString(), "{\"task\":\"task13\"}", owner, token);
        return eventId;
    }

    private void insertAdminClaim(UUID caseId) {
        jdbc.update("INSERT INTO warranty_deadline_claim(id,warranty_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'ADMIN_SLA',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), caseId.toString());
    }

    private UUID insertWarrantyCase(String status) {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), caseId = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','cat',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "task13-fence-" + listing);
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "title", "desc");
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,admin_deadline,version,opened_at,closed_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT',?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CASE WHEN ?='RESOLVED' THEN CURRENT_TIMESTAMP(6) ELSE NULL END,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            caseId.toString(), order.toString(), "task13-fence-" + caseId, buyer.toString(), seller.toString(), status, status);
        return caseId;
    }

    private UUID user() {
        return UUID.randomUUID();
    }

    private Object cleanupTask(String id, String objectKey, String owner, String token) throws Exception {
        Class<?> type = Class.forName("com.example.campusmarket.storage.StorageCleanupScheduler$Task");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(id, objectKey, owner, token);
    }

    private boolean invokeCleanup(String name, Object task, Object... args) throws Exception {
        Method method = java.util.Arrays.stream(StorageCleanupScheduler.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        Object[] parameters = new Object[args.length + 1];
        parameters[0] = task;
        System.arraycopy(args, 0, parameters, 1, args.length);
        return (Boolean) method.invoke(cleanup, parameters);
    }
}
