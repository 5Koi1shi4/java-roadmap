package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.messaging.InboxRepository;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.storage.MinioPrivateObjectStorage;
import com.example.campusmarket.storage.PrivateObjectStorage;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三轮故障演练套件；每个嵌套类仅启动当前轮次需要的真实容器。 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class RecoveryDrillIT {
    private abstract static class DrillContainers {
        protected static void start(Stream<? extends Startable> containers) {
            Startables.deepStart(containers).join();
        }

        protected static void common(DynamicPropertyRegistry registry,
                                     MySQLContainer<?> mysql,
                                     GenericContainer<?> redis) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

            registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
            registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
            registry.add("campus.market.order.deadline.enabled", () -> "false");
            registry.add("campus.market.search.dispatcher.enabled", () -> "false");
            registry.add("campus.market.payment.reconciliation.enabled", () -> "false");
            registry.add("campus.market.dispute.deadline.enabled", () -> "false");
            registry.add("campus.market.dispute.return-reconciliation.enabled", () -> "false");
            registry.add("campus.market.warranty.deadline.enabled", () -> "false");
            registry.add("spring.task.scheduling.enabled", () -> "false");

            registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
            registry.add("spring.rabbitmq.port", () -> "1");
            registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
            registry.add("campus.market.storage.endpoint", () -> "http://127.0.0.1:1");
        }

        protected static void assertInvariants(JdbcTemplate jdbc) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE status='SUCCEEDED'", Integer.class)).isGreaterThan(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE status='SUCCEEDED'", Integer.class)).isGreaterThan(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE status='SETTLED'", Integer.class)).isGreaterThan(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isGreaterThan(0);
            assertThat(jdbc.queryForObject(
                "SELECT COALESCE(MIN(available_quantity),0) FROM listing", Integer.class))
                .isGreaterThanOrEqualTo(0);
            assertThat(jdbc.queryForObject(
                "SELECT COALESCE(MIN(quarantined_quantity),0) FROM listing", Integer.class))
                .isGreaterThanOrEqualTo(0);
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement "
                    + "GROUP BY business_key HAVING COUNT(*)>1) d", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_order "
                    + "WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement s JOIN settlement s2 "
                    + "ON s.order_id=s2.order_id AND s.id<>s2.id", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration_outbox "
                    + "WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_event "
                    + "WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispute_evidence e "
                    + "LEFT JOIN dispute_case c ON c.id=e.dispute_case_id "
                    + "WHERE c.id IS NULL", Integer.class))
                .isZero();
        }

        protected static BusinessFacts seedBusinessFacts(JdbcTemplate jdbc) {
            UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID(), listing = UUID.randomUUID();
            UUID order = UUID.randomUUID(), payment = UUID.randomUUID(), refund = UUID.randomUUID();
            jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),(?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                buyer.toString(), buyer + "@stu.example.edu.cn", "hash", seller.toString(), seller + "@stu.example.edu.cn", "hash");
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'恢复演练','教材',100,1,'ON_SALE',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                listing.toString(), seller.toString(), "恢复演练商品");
            jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,warranty_days,warranty_scope_snapshot,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,NULL,NULL,100,'SETTLED',1,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                order.toString(), buyer.toString(), seller.toString(), listing.toString(), "恢复演练商品", "恢复演练");
            jdbc.update("INSERT INTO payment_order(id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                payment.toString(), order.toString(), "simulated", "recovery-pay-" + order, "recovery-payment-" + order);
            jdbc.update("INSERT INTO refund_order(id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,paid_amount_fen,amount_fen,successful_refund_fen,reserved_refund_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,'DRILL',?,?,100,20,0,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                refund.toString(), order.toString(), payment.toString(), "simulated", "recovery-refund-" + order, order.toString(), 100L, "recovery-refund-ref-" + order);
            jdbc.update("UPDATE payment_order SET paid_amount_fen=100,successful_refund_fen=20,reserved_refund_fen=0 WHERE id=?", payment.toString());
            jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) VALUES (?,?,100,20,80,'SETTLED',CURRENT_TIMESTAMP(6))",
                UUID.nameUUIDFromBytes(("drill-settlement:" + order).getBytes(StandardCharsets.UTF_8)).toString(), order.toString());
            jdbc.update("INSERT INTO inventory_movement(id,business_key,listing_id,order_id,reason,quantity_delta,created_at) VALUES (?,?,?,?,'ORDER_RESERVED',-1,CURRENT_TIMESTAMP(6)),(?,?,?,?,'RETURN_QUARANTINED',1,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "drill-reserve-" + order, listing.toString(), order.toString(), UUID.randomUUID().toString(), "drill-return-" + order, listing.toString(), order.toString());
            return new BusinessFacts(buyer, seller, listing, order, payment, refund);
        }

        protected record BusinessFacts(UUID buyer, UUID seller, UUID listing, UUID order, UUID payment, UUID refund) {}
    }

    @Nested
    @Order(1)
    @SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class RabbitRound extends RabbitContainers {
        @Autowired private JdbcTemplate jdbc;
        @Autowired private OutboxDispatcher outbox;
        @Autowired private InboxRepository inbox;

        @Test
        void round1RabbitDisconnectLeavesOutboxAndExpiredInboxThenRecovers() {
            BusinessFacts facts = seedBusinessFacts(jdbc);
            UUID event = UUID.randomUUID();
            insertOutbox(event, "ORDER_PAID", facts.order());
            insertExpiredInbox(event);

            PROXY.setConnectionCut(true);
            try {
                outbox.dispatchOnce(10, Duration.ofSeconds(2));
                assertThat(status("integration_outbox", event)).isIn("NEW", "PUBLISHING");
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM integration_outbox WHERE event_id=?", Integer.class, event.toString())).isGreaterThan(0);
            } finally {
                PROXY.setConnectionCut(false);
            }

            // Simulate a crashed publisher by expiring, rather than clearing, its lease.
            jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6), "
                    + "lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) "
                    + "WHERE event_id=?", event.toString());
            assertThat(outbox.dispatchOnce(10, Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(status("integration_outbox", event)).isEqualTo("PUBLISHED");

            assertThat(inbox.process("recovery-drill", event, Duration.ofSeconds(30), claim -> {
                assertThat(jdbc.update("UPDATE trade_order SET status='AWAITING_HANDOFF',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='SETTLED'", facts.order().toString())).isEqualTo(1);
                jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?, 'ORDER_HANDOFF_CONFIRMED',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                    UUID.randomUUID().toString(), UUID.nameUUIDFromBytes(("derived:" + event).getBytes(StandardCharsets.UTF_8)).toString(), facts.order().toString(), 2L, "{\"orderId\":\"" + facts.order() + "\"}");
            })).isTrue();
            assertThat(jdbc.queryForObject(
                "SELECT status FROM consumed_event WHERE consumer_name='recovery-drill' AND event_id=?",
                String.class, event.toString())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, facts.order().toString())).isEqualTo("AWAITING_HANDOFF");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_HANDOFF_CONFIRMED' AND aggregate_id=?", Integer.class, facts.order().toString())).isEqualTo(1);
            assertInvariants(jdbc);
        }

        private void insertOutbox(UUID event, String type, UUID aggregate) {
            jdbc.update("INSERT INTO integration_outbox "
                    + "(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,status,"
                    + "attempt_count,available_at,created_at) VALUES (?,?,?, ?,1,1,CAST(? AS JSON),"
                    + "'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                event.toString(), event.toString(), type, aggregate.toString(), "{}");
        }

        private void insertExpiredInbox(UUID event) {
            jdbc.update("INSERT INTO consumed_event "
                    + "(id,consumer_name,event_id,status,owner_id,claim_token,lease_until,attempt_count,created_at) "
                    + "VALUES (?,?,?,'PROCESSING','old-owner','old-token',"
                    + "DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),1,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "recovery-drill", event.toString());
        }

        private String status(String table, UUID id) {
            return jdbc.queryForObject("SELECT status FROM " + table + " WHERE event_id=?",
                String.class, id.toString());
        }
    }

    @Nested
    @Order(2)
    @SpringBootTest(classes = CampusMarketApplication.class)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class SearchRound extends SearchContainers {
        @Autowired private JdbcTemplate jdbc;
        @Autowired private SearchOutboxDispatcher searchOutbox;
        @Autowired private ProductSearchPort search;

        @Test
        void round2ElasticsearchDisconnectLeavesSearchOutboxThenCatchesUp() {
            UUID seller = insertUser();
            BusinessFacts facts = seedBusinessFacts(jdbc);
            UUID listing = UUID.randomUUID();
            jdbc.update("INSERT INTO listing "
                    + "(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,"
                    + "created_at,updated_at) VALUES (?,?,?,'desc','教材',100,2,'ON_SALE',1,"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                listing.toString(), seller.toString(), "故障演练教材");

            UUID event = UUID.randomUUID();
            jdbc.update("INSERT INTO search_outbox "
                    + "(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) "
                    + "VALUES (?,?,1,'LISTING_PUBLISHED',CAST(? AS JSON),'NEW',0,"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                event.toString(), listing.toString(), "{}");
            UUID seededEvent = UUID.randomUUID();
            jdbc.update("INSERT INTO search_outbox "
                    + "(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) "
                    + "VALUES (?,?,1,'LISTING_PUBLISHED',CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                seededEvent.toString(), facts.listing().toString(), "{}");

            PROXY.setConnectionCut(true);
            try {
                searchOutbox.dispatchOnce(10, Duration.ofSeconds(2));
                assertThat(status(event)).isIn("NEW", "PUBLISHING");
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_outbox WHERE id=?", Integer.class, event.toString())).isGreaterThan(0);
            } finally {
                PROXY.setConnectionCut(false);
            }

            jdbc.update("UPDATE search_outbox SET available_at=CURRENT_TIMESTAMP(6), "
                    + "lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
                event.toString());
            assertThat(searchOutbox.dispatchOnce(10, Duration.ofSeconds(30))).isEqualTo(2);
            search.refresh();
            assertThat(status(event)).isEqualTo("PUBLISHED");
            Set<String> indexed = Set.copyOf(search.search(
                new ProductSearchPort.SearchRequest(null, null, null, null, 0, 20))
                .items().stream().map(ProductSearchPort.SearchItem::listingId).toList());
            assertThat(indexed).contains(listing.toString());
            Set<String> mysqlOnSale = Set.copyOf(jdbc.query(
                "SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0",
                (rs, n) -> rs.getString(1)));
            assertThat(indexed).isEqualTo(mysqlOnSale);
            assertInvariants(jdbc);
        }

        private UUID insertUser() {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO campus_user "
                    + "(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id.toString(), id + "@stu.example.edu.cn", "hash");
            return id;
        }

        private String status(UUID id) {
            return jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?",
                String.class, id.toString());
        }
    }

    @Nested
    @Order(3)
    @SpringBootTest(classes = CampusMarketApplication.class)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class StorageRound extends StorageContainers {
        @LocalServerPort private int port;
        @Autowired private JdbcTemplate jdbc;
        @Autowired private JwtService jwt;
        @Autowired private StorageCleanupScheduler cleanup;
        @Autowired private PrivateObjectStorage storage;
        private final HttpClient client = HttpClient.newHttpClient();

        private static final int STORAGE_RECOVERY_ATTEMPTS = 8;
        private static final Duration STORAGE_RECOVERY_POLL = Duration.ofMillis(250);

        @Test
        void round3MinioDisconnectLeavesCleanupPendingThenDeletesAfterRecovery() {
            seedBusinessFacts(jdbc);
            assertEvidenceAclOverHttp();
            UUID submitter = insertUser();
            UUID session = UUID.randomUUID();
            String objectKey = "cleanup-drill/" + session;
            byte[] object = "recovery-drill-object".getBytes(StandardCharsets.UTF_8);
            storage.put(objectKey, new ByteArrayInputStream(object), object.length, "text/plain");
            try (var uploaded = storage.open(objectKey)) {
                assertThat(uploaded.readAllBytes()).containsExactly(object);
            } catch (Exception failure) {
                throw new AssertionError("failed to verify the real MinIO fixture", failure);
            }
            jdbc.update("INSERT INTO object_upload_session "
                    + "(id,submitted_by,purpose,object_key,status,expires_at,created_at,updated_at) "
                    + "VALUES (?,?, 'LISTING_MEDIA',?,'OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE),"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                session.toString(), submitter.toString(), objectKey);

            PROXY.setConnectionCut(true);
            try {
                cleanup.runOnce(10);
                assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=? "
                    + "AND status IN ('PENDING','PROCESSING')", Integer.class,
                    "listing-upload:" + session)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class,
                    "listing-upload:" + session)).isGreaterThan(0);
                assertThat(jdbc.queryForObject("SELECT failure_class FROM storage_cleanup_task WHERE cleanup_business_key=?", String.class,
                    "listing-upload:" + session)).isEqualTo("TRANSIENT");
            } finally {
                PROXY.setConnectionCut(false);
            }

            String businessKey = "listing-upload:" + session;
            recoverCleanup(businessKey);
            assertThat(jdbc.queryForObject(
                "SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?",
                String.class, businessKey)).isEqualTo("COMPLETED");
            assertThatThrownBy(() -> storage.open(objectKey))
                .isInstanceOf(MinioPrivateObjectStorage.ObjectNotFoundException.class);
            assertInvariants(jdbc);
        }

        /**
         * Re-drive the production cleanup scheduler until the restored MinIO proxy converges.
         * The database clock makes both PENDING backoff and PROCESSING lease expiry eligible;
        * this never changes the task status or marks the task complete from the test.
         */
        private void recoverCleanup(String businessKey) {
            int attempts = 0;
            int completed = 0;
            while (attempts < STORAGE_RECOVERY_ATTEMPTS) {
                jdbc.update("UPDATE storage_cleanup_task SET run_after=CURRENT_TIMESTAMP(6), "
                        + "lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) "
                        + "WHERE cleanup_business_key=? AND status IN ('PENDING','PROCESSING')", businessKey);
                attempts++;
                completed = cleanup.runOnce(10);
                if (completed == 1) {
                    return;
                }
                if (attempts < STORAGE_RECOVERY_ATTEMPTS) {
                    try {
                        Thread.sleep(STORAGE_RECOVERY_POLL.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("storage cleanup recovery interrupted after "
                                + attempts + " attempts", interrupted);
                    }
                }
            }

            String details = jdbc.queryForObject(
                "SELECT CONCAT('status=',status,', failure_class=',COALESCE(failure_class,'NULL'),"
                    + "', attempts=',attempt_count,', run_after=',COALESCE(run_after,'NULL'),"
                    + "', lease_until=',COALESCE(lease_until,'NULL')) "
                    + "FROM storage_cleanup_task WHERE cleanup_business_key=?",
                String.class, businessKey);
            assertThat(completed)
                .withFailMessage("cleanup did not converge after %s attempts: %s", attempts, details)
                .isEqualTo(1);
        }

        private UUID insertUser() {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO campus_user "
                    + "(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id.toString(), id + "@stu.example.edu.cn", "hash");
            return id;
        }

        private void assertEvidenceAclOverHttp() {
            UUID buyer = insertUser(), seller = insertUser(), other = insertUser(), listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID();
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?, '证据商品','描述','教材',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString());
            jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,t0,acceptance_deadline,trial_deadline,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, '证据商品','描述',100,1,100,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 167 HOUR),100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString());
            jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
            try {
                HttpResponse<String> uploaded = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "proof.png", "image/png",
                    java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
                assertThat(uploaded.statusCode()).isEqualTo(201);
                String evidenceId = field(uploaded.body(), "evidenceId");
                assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(200);
                assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(seller)).statusCode()).isEqualTo(200);
                assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(other)).statusCode()).isEqualTo(404);
            } catch (Exception failure) {
                throw new AssertionError("HTTP evidence ACL drill failed", failure);
            }
        }

        private String token(UUID user) { return jwt.issue(new AuthenticatedUser(user, Set.of("ROLE_USER"))); }
        private HttpResponse<String> multipart(String path, String bearer, String filename, String type, byte[] bytes) throws Exception {
            String boundary = "----recovery" + UUID.randomUUID();
            byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[prefix.length + bytes.length + suffix.length];
            System.arraycopy(prefix, 0, body, 0, prefix.length); System.arraycopy(bytes, 0, body, prefix.length, bytes.length); System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + bearer).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        private HttpResponse<String> get(String path, String bearer) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        private static String field(String json, String name) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json);
            if (!matcher.find()) throw new AssertionError("missing " + name + " in " + json);
            return matcher.group(1);
        }
    }

    private abstract static class RabbitContainers extends DrillContainers {
        protected static final Network NETWORK = Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
            .withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13.7-management"))
            .withNetwork(NETWORK).withNetworkAliases("rabbitmq");
        protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;

        static {
            start(Stream.of(MYSQL, REDIS, RABBIT, TOXIPROXY));
            PROXY = TOXIPROXY.getProxy(RABBIT, 5672);
        }

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            common(registry, MYSQL, REDIS);
            registry.add("spring.rabbitmq.host", PROXY::getContainerIpAddress);
            registry.add("spring.rabbitmq.port", PROXY::getProxyPort);
            registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        }

        @AfterAll
        static void stop() {
            Stream.of(TOXIPROXY, RABBIT, MYSQL, REDIS).forEach(GenericContainer::stop);
            NETWORK.close();
        }
    }

    private abstract static class SearchContainers extends DrillContainers {
        protected static final Network NETWORK = Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
            .withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        private static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile(
            "campus-market/elasticsearch:8.18.8-smartcn", true)
            .withDockerfile(Path.of("docker/elasticsearch/Dockerfile"));
        protected static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn")
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withNetwork(NETWORK).withNetworkAliases("elasticsearch");
        protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;

        static {
            ES.setImage(ES_IMAGE);
            start(Stream.of(MYSQL, REDIS, ES, TOXIPROXY));
            PROXY = TOXIPROXY.getProxy(ES, 9200);
        }

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            common(registry, MYSQL, REDIS);
            registry.add("spring.elasticsearch.uris",
                () -> "http://" + PROXY.getContainerIpAddress() + ":" + PROXY.getProxyPort());
        }

        @AfterAll
        static void stop() {
            Stream.of(TOXIPROXY, ES, MYSQL, REDIS).forEach(GenericContainer::stop);
            NETWORK.close();
        }
    }

    private abstract static class StorageContainers extends DrillContainers {
        protected static final Network NETWORK = Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
            .withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withCommand("server /data --console-address :9001")
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
            .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000, 9001)
            .waitingFor(Wait.forListeningPort());
        protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;

        static {
            start(Stream.of(MYSQL, REDIS, MINIO, TOXIPROXY));
            PROXY = TOXIPROXY.getProxy(MINIO, 9000);
        }

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            common(registry, MYSQL, REDIS);
            registry.add("campus.market.storage.endpoint",
                () -> "http://" + PROXY.getContainerIpAddress() + ":" + PROXY.getProxyPort());
        }

        @AfterAll
        static void stop() {
            Stream.of(TOXIPROXY, MINIO, MYSQL, REDIS).forEach(GenericContainer::stop);
            NETWORK.close();
        }
    }
}
