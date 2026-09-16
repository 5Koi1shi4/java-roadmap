package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.messaging.ReliableEventConsumer;
import com.example.campusmarket.messaging.RabbitTopology;
import com.example.campusmarket.storage.MinioPrivateObjectStorage;
import com.example.campusmarket.storage.PrivateObjectStorage;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import com.github.dockerjava.api.command.CreateContainerCmd;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
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
import java.util.List;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.AcknowledgeMode;
import org.awaitility.Awaitility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三轮故障演练套件；每个嵌套类仅启动当前轮次需要的真实容器。 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class RecoveryDrillIT {
    private abstract static class DrillContainers {
        protected static void start(Stream<? extends Startable> containers) {
            Startables.deepStart(containers).join();
        }

        /**
         * 在父类中创建 Docker 回调，避免子类静态初始化期间并发容器线程回调子类 lambda，
         * 从而等待正在执行 deepStart().join() 的子类初始化锁。
         */
        protected static Consumer<CreateContainerCmd> memoryLimit(long bytes) {
            return command -> command.getHostConfig().withMemory(bytes);
        }

        protected static void common(DynamicPropertyRegistry registry,
                                     MySQLContainer<?> mysql,
                                     GenericContainer<?> redis) {
            ResourceServerTestSupport.register(registry);
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.data.redis.url",
                redis == null
                    ? () -> "redis://127.0.0.1:1"
                    : () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

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
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen<0 "
                + "OR reserved_refund_fen<0 OR successful_refund_fen+reserved_refund_fen>paid_amount_fen", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT order_id FROM settlement GROUP BY order_id HAVING COUNT(*)>1) s",
                Integer.class)).isZero();
        }

        protected static void assertSearchEqualsMysql(JdbcTemplate jdbc, ProductSearchPort search) {
            search.refresh();
            Set<String> indexed = Set.copyOf(search.search(
                new ProductSearchPort.SearchRequest(null, null, null, null, 0, 100))
                .items().stream().map(ProductSearchPort.SearchItem::listingId).toList());
            Set<String> mysqlOnSale = Set.copyOf(jdbc.query(
                "SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0",
                (rs, n) -> rs.getString(1)));
            assertThat(indexed).isEqualTo(mysqlOnSale);
        }

        protected static BusinessFacts seedBusinessFacts(JdbcTemplate jdbc) {
            UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID(), listing = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'恢复演练','教材',100,1,'ON_SALE',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                listing.toString(), seller.toString(), "恢复演练商品");
            jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,warranty_days,warranty_scope_snapshot,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,NULL,NULL,100,'PENDING_PAYMENT',1,NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                order.toString(), buyer.toString(), seller.toString(), listing.toString(), "恢复演练商品", "恢复演练");
            return new BusinessFacts(buyer, seller, listing, order);
        }

        protected record BusinessFacts(UUID buyer, UUID seller, UUID listing, UUID order) {}

        protected static void projectAllOnSale(JdbcTemplate jdbc, SearchOutboxDispatcher dispatcher) {
            List<String> ids = jdbc.query("SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0",
                (rs, n) -> rs.getString(1));
            for (String id : ids) {
                Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE listing_id=? AND status IN ('NEW','PUBLISHING')",
                    Integer.class, id);
                if (present != null && present == 0) {
                    jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) "
                        + "SELECT UUID(),id,version,'LISTING_PUBLISHED',CAST('{}' AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM listing WHERE id=?", id);
                }
            }
            dispatcher.dispatchOnce(100, Duration.ofSeconds(30));
        }

        protected static AclFacts seedEvidenceAcl(JdbcTemplate jdbc, PrivateObjectStorage storage) {
            UUID buyer = insertUser(jdbc), seller = insertUser(jdbc), other = insertUser(jdbc), admin = insertUser(jdbc);
            UUID listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID(), evidence = UUID.randomUUID();
            String objectKey = "recovery-acl/" + evidence;
            byte[] body = "recovery-acl-proof".getBytes(StandardCharsets.UTF_8);
            storage.put(objectKey, new ByteArrayInputStream(body), body.length, "image/png");
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?, 'ACL 商品','描述','教材',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString());
            jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,t0,acceptance_deadline,trial_deadline,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, 'ACL 商品','描述',100,1,100,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 167 HOUR),100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString());
            jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
            jdbc.update("INSERT INTO dispute_evidence(id,dispute_case_id,submitted_by,object_key,media_type,size_bytes,created_at) VALUES (?,?,?,?, 'image/png',?,CURRENT_TIMESTAMP(6))",
                evidence.toString(), dispute.toString(), buyer.toString(), objectKey, body.length);
            return new AclFacts(buyer, seller, other, admin, dispute, evidence);
        }

        protected static void assertEvidenceAclOverHttp(int port, AclFacts facts) {
            HttpClient client = HttpClient.newHttpClient();
            try {
                String path = "/api/disputes/" + facts.dispute() + "/evidence/" + facts.evidence() + "/content";
                assertThat(get(client, port, path, ResourceServerTestSupport.token(facts.buyer(), Set.of("ROLE_USER"))).statusCode()).isEqualTo(200);
                assertThat(get(client, port, path, ResourceServerTestSupport.token(facts.seller(), Set.of("ROLE_USER"))).statusCode()).isEqualTo(200);
                assertThat(get(client, port, path, ResourceServerTestSupport.token(facts.other(), Set.of("ROLE_USER"))).statusCode()).isEqualTo(404);
                assertThat(get(client, port, path, ResourceServerTestSupport.token(facts.admin(), Set.of("ROLE_ADMIN"))).statusCode()).isEqualTo(404);
            } catch (Exception failure) {
                throw new AssertionError("HTTP evidence ACL drill failed", failure);
            }
        }

        private static UUID insertUser(JdbcTemplate jdbc) {
            return UUID.randomUUID();
        }

        private static HttpResponse<String> get(HttpClient client, int port, String path, String bearer) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        protected record AclFacts(UUID buyer, UUID seller, UUID other, UUID admin, UUID dispute, UUID evidence) {}
    }

    @Nested
    @Order(1)
    @SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
    @ActiveProfiles("local")
    @TestPropertySource(properties = {
        "server.port=" + RecoveryDrillResourcePlan.RABBIT_HTTP_PORT,
        "campus.market.payment.provider-url=" + "http://localhost:" + RecoveryDrillResourcePlan.RABBIT_HTTP_PORT + "/simulated-provider"
    })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class RabbitRound extends RabbitContainers {
        @Autowired private JdbcTemplate jdbc;
        @Autowired private OutboxDispatcher outbox;
        @Autowired private RabbitTemplate rabbit;
        @Autowired private ReliableEventConsumer consumer;
        @Autowired private MeterRegistry metrics;
        @LocalServerPort private int port;

        @Test
        void round1RabbitDisconnectLeavesOutboxAndExpiredInboxThenRecovers() {
            BusinessFacts facts = seedBusinessFacts(jdbc);
            String reference = createPendingPaymentOverHttp(facts.order(), facts.buyer());
            UUID event = triggerSuccessfulPaymentWebhook(facts.order(), reference);
            UUID payment = jdbc.queryForObject("SELECT id FROM payment_order WHERE order_id=? AND status='SUCCEEDED'",
                (rs, n) -> UUID.fromString(rs.getString(1)), facts.order().toString());
            jdbc.update("UPDATE trade_order SET status='PENDING_PAYMENT',handoff_deadline=NULL,version=1 WHERE id=?", facts.order().toString());
            insertExpiredInbox(event);

            PROXY.setConnectionCut(true);
            try {
                outbox.dispatchOnce(10, Duration.ofSeconds(30));
                assertThat(status("integration_outbox", event)).isEqualTo("NEW");
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM integration_outbox WHERE event_id=?", Integer.class, event.toString())).isGreaterThan(0);
                String meterDiagnostics = metrics.getMeters().stream().map(meter -> meter.getId().toString()).sorted()
                    .collect(java.util.stream.Collectors.joining("; "));
                io.micrometer.core.instrument.Counter retryCounter = metrics.find("campus.market.retry.total.OUTBOX")
                    .tag("result", "RETRY").counter();
                assertThat(retryCounter)
                    .withFailMessage("retry meter missing; outbox status=%s attempt=%s meters=%s",
                        status("integration_outbox", event),
                        jdbc.queryForObject("SELECT attempt_count FROM integration_outbox WHERE event_id=?", Integer.class, event.toString()),
                        meterDiagnostics)
                    .isNotNull();
                assertThat(retryCounter.count()).isGreaterThan(0.0);
            } finally {
                PROXY.setConnectionCut(false);
            }

            // Simulate a crashed publisher by expiring, rather than clearing, its lease.
            jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6), "
                    + "lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) "
                    + "WHERE event_id=?", event.toString());
            assertThat(outbox.dispatchOnce(10, Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(status("integration_outbox", event)).isEqualTo("PUBLISHED");

            SimpleMessageListenerContainer listener = new SimpleMessageListenerContainer(rabbit.getConnectionFactory());
            listener.setQueueNames(RabbitTopology.EVENT_QUEUE);
            listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            listener.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener) consumer::onMessage);
            listener.start();
            try {
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject(
                        "SELECT status FROM consumed_event WHERE consumer_name='campus-market-order' AND event_id=?",
                        String.class, event.toString())).isEqualTo("COMPLETED"));
            } finally {
                listener.stop();
            }
            Integer ready = rabbit.execute(channel -> channel.queueDeclarePassive(RabbitTopology.EVENT_QUEUE).getMessageCount());
            assertThat(ready).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, facts.order().toString())).isEqualTo("AWAITING_HANDOFF");
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT paid_amount_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(100L);
            assertInvariants(jdbc);
        }

        private UUID triggerSuccessfulPaymentWebhook(UUID orderId, String reference) {
            try {
                HttpResponse<String> provider = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/simulated-provider/payments/" + reference + "/SUCCEEDED"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(provider.statusCode()).withFailMessage("provider status update failed: status=%s body=%s", provider.statusCode(), provider.body()).isEqualTo(200);
                String providerEvent = "recovery-payment-" + UUID.randomUUID();
                String body = "{\"providerEventId\":\"" + providerEvent + "\",\"type\":\"PAYMENT\",\"providerReference\":\""
                    + reference + "\",\"amountFen\":100,\"status\":\"SUCCEEDED\",\"occurredAt\":\""
                    + java.time.Instant.now() + "\",\"orderId\":\"" + orderId + "\"}";
                String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
                String nonce = UUID.randomUUID().toString();
                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/payment-webhooks/simulated"))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Payment-Timestamp", timestamp).header("X-Payment-Nonce", nonce)
                    .header("X-Payment-Signature", hmac(timestamp + "\n" + nonce + "\n" + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(response.statusCode()).withFailMessage("webhook failed: status=%s body=%s", response.statusCode(), response.body()).isEqualTo(200);
                String eventId = jdbc.query("SELECT event_id FROM integration_outbox WHERE event_type='ORDER_PAID' "
                    + "AND aggregate_id=? ORDER BY created_at DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, orderId.toString());
                String diagnostics = jdbc.query("SELECT CONCAT('payment=',status,',ref=',COALESCE(provider_reference,'NULL')) FROM payment_order WHERE order_id=?",
                    rs -> rs.next() ? rs.getString(1) : "payment=missing", orderId.toString());
                String callback = jdbc.query("SELECT CONCAT('callback=',status) FROM payment_callback_event WHERE provider_event_id=?",
                    rs -> rs.next() ? rs.getString(1) : "callback=missing", providerEvent);
                assertThat(eventId).withFailMessage("webhook returned 200 but ORDER_PAID missing: %s, %s, body=%s", diagnostics, callback, response.body()).isNotNull();
                return UUID.fromString(eventId);
            } catch (Exception failure) {
                throw new AssertionError("payment webhook trigger failed", failure);
            }
        }

        private String createPendingPaymentOverHttp(UUID orderId, UUID buyerId) {
            try {
                String key = "recovery-payment-" + orderId;
                HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/orders/" + orderId + "/payments"))
                    .header("Authorization", "Bearer " + ResourceServerTestSupport.token(buyerId, Set.of("ROLE_USER")))
                    .header("Content-Type", "application/json; charset=UTF-8").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(response.statusCode()).withFailMessage("payment create failed: status=%s body=%s", response.statusCode(), response.body()).isEqualTo(201);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"providerReference\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
                assertThat(matcher.find()).withFailMessage("payment response has no providerReference: %s", response.body()).isTrue();
                return matcher.group(1);
            } catch (Exception failure) {
                throw new AssertionError("payment create trigger failed", failure);
            }
        }

        private String hmac(String text) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("local-only-payment-secret-change-me".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        }

        private void insertExpiredInbox(UUID event) {
            jdbc.update("INSERT INTO consumed_event "
                    + "(id,consumer_name,event_id,status,owner_id,claim_token,lease_until,attempt_count,created_at) "
                    + "VALUES (?,?,?,'PROCESSING','old-owner','old-token',"
                    + "DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),1,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "campus-market-order", event.toString());
        }

        private String status(String table, UUID id) {
            return jdbc.queryForObject("SELECT status FROM " + table + " WHERE event_id=?",
                String.class, id.toString());
        }
    }

    @Nested
    @Order(2)
    @SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class SearchRound extends SearchContainers {
        @LocalServerPort private int port;
        @Autowired private JdbcTemplate jdbc;
        @Autowired private SearchOutboxDispatcher searchOutbox;
        @Autowired private ProductSearchPort search;
        @Autowired private MeterRegistry metrics;

        @Test
        void round2ElasticsearchDisconnectLeavesSearchOutboxThenCatchesUp() throws Exception {
            UUID seller = insertUser();
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
            PROXY.setConnectionCut(true);
            try {
                HttpResponse<String> unavailable = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/search?keyword=%E6%95%85%E9%9A%9C&size=20"))
                        .header("Authorization", "Bearer " + ResourceServerTestSupport.token(seller, Set.of("ROLE_USER")))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(unavailable.statusCode()).isEqualTo(503);
                searchOutbox.dispatchOnce(10, Duration.ofSeconds(2));
                assertThat(status(event)).isIn("NEW", "PUBLISHING");
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_outbox WHERE id=?", Integer.class, event.toString())).isGreaterThan(0);
                assertThat(metrics.get("campus.market.search.total").tag("result", "FAILURE").counter().count()).isGreaterThan(0.0);
            } finally {
                PROXY.setConnectionCut(false);
            }

            jdbc.update("UPDATE search_outbox SET available_at=CURRENT_TIMESTAMP(6), "
                    + "lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
                event.toString());
            int expectedRecovered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_outbox WHERE listing_id=? AND status IN ('NEW','PUBLISHING')",
                Integer.class, listing.toString());
            assertThat(searchOutbox.dispatchOnce(10, Duration.ofSeconds(30))).isEqualTo(expectedRecovered);
            search.refresh();
            assertThat(status(event)).isEqualTo("PUBLISHED");
            assertSearchEqualsMysql(jdbc, search);
            assertInvariants(jdbc);
        }

        private UUID insertUser() {
            return UUID.randomUUID();
        }

        private String status(UUID id) {
            return jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?",
                String.class, id.toString());
        }
    }

    @Nested
    @Order(3)
    @SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class StorageRound extends StorageContainers {
        @LocalServerPort private int port;
        @Autowired private JdbcTemplate jdbc;
        @Autowired private StorageCleanupScheduler cleanup;
        @Autowired private PrivateObjectStorage storage;
        @Autowired private MeterRegistry metrics;
        private final HttpClient client = HttpClient.newHttpClient();
        private UUID evidenceDispute;
        private UUID evidenceBuyer;

        private static final int STORAGE_RECOVERY_ATTEMPTS = 8;
        private static final Duration STORAGE_RECOVERY_POLL = Duration.ofMillis(250);
        private static final byte[] VALID_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        @Test
        void round3MinioDisconnectLeavesCleanupPendingThenDeletesAfterRecovery() throws Exception {
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
                HttpResponse<String> unavailable = multipart("/api/disputes/" + evidenceDispute + "/evidence", token(evidenceBuyer),
                    "during-outage.png", "image/png", VALID_PNG);
                assertThat(unavailable.statusCode()).isEqualTo(503);
                cleanup.runOnce(10);
                assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=? "
                    + "AND status IN ('PENDING','PROCESSING')", Integer.class,
                    "listing-upload:" + session)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class,
                    "listing-upload:" + session)).isGreaterThan(0);
                assertThat(jdbc.queryForObject("SELECT failure_class FROM storage_cleanup_task WHERE cleanup_business_key=?", String.class,
                    "listing-upload:" + session)).isEqualTo("TRANSIENT");
                assertThat(metrics.get("campus.market.storage.operation.total").tag("result", "FAILURE").counter().count()).isGreaterThan(0.0);
            } finally {
                PROXY.setConnectionCut(false);
            }

            String businessKey = "listing-upload:" + session;
            String disputeBusinessKey = jdbc.queryForObject("SELECT cleanup_business_key FROM storage_cleanup_task "
                + "WHERE cleanup_business_key LIKE 'dispute-upload:%' AND status='PENDING' ORDER BY created_at DESC LIMIT 1", String.class);
            recoverCleanup(businessKey);
            recoverCleanup(disputeBusinessKey);
            assertThat(jdbc.queryForObject(
                "SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?",
                String.class, businessKey)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?",
                String.class, disputeBusinessKey)).isEqualTo("COMPLETED");
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
            return UUID.randomUUID();
        }

        private void assertEvidenceAclOverHttp() {
            UUID buyer = insertUser(), seller = insertUser(), other = insertUser(), admin = insertAdmin(), listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID();
            evidenceBuyer = buyer;
            evidenceDispute = dispute;
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
                assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", adminToken(admin)).statusCode()).isEqualTo(404);
            } catch (Exception failure) {
                throw new AssertionError("HTTP evidence ACL drill failed", failure);
            }
        }

        private UUID insertAdmin() {
            return insertUser();
        }
        private String adminToken(UUID user) { return ResourceServerTestSupport.token(user, Set.of("ROLE_ADMIN")); }

        private String token(UUID user) { return ResourceServerTestSupport.token(user, Set.of("ROLE_USER")); }
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
        private static final long MYSQL_MEMORY_BYTES = 512L * 1024L * 1024L;
        private static final long ELASTICSEARCH_MEMORY_BYTES = 768L * 1024L * 1024L;
        protected static final Network NETWORK = Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local")
            .withNetwork(NETWORK).withNetworkAliases("mysql")
            .withCreateContainerCmdModifier(memoryLimit(MYSQL_MEMORY_BYTES));
        private static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile(
            "campus-market/elasticsearch:9.4.5-smartcn", true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));
        protected static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("campus-market/elasticsearch:9.4.5-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:9.4.5"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
            .withCreateContainerCmdModifier(memoryLimit(ELASTICSEARCH_MEMORY_BYTES))
            .withNetwork(NETWORK).withNetworkAliases("elasticsearch");
        protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;

        static {
            ES.setImage(ES_IMAGE);
            start(Stream.of(MYSQL, ES, TOXIPROXY));
            PROXY = TOXIPROXY.getProxy(ES, 9200);
        }

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            // SearchRound 只调用 MySQL 与搜索端口；Redis bean 仅为生产认证适配器提供依赖，
            // 本轮没有认证/验证码调用，因此显式指向不可达端口，避免启动无用 Redis 容器。
            common(registry, MYSQL, null);
            registry.add("spring.elasticsearch.uris",
                () -> "http://" + PROXY.getContainerIpAddress() + ":" + PROXY.getProxyPort());
        }

        @AfterAll
        static void stop() {
            Stream.of(TOXIPROXY, ES, MYSQL).forEach(GenericContainer::stop);
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

    private static ImageFromDockerfile smartCnImage() {
        return new ImageFromDockerfile("campus-market/elasticsearch:9.4.5-smartcn", true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));
    }

    private static ElasticsearchContainer elasticsearch(Network network, ImageFromDockerfile image, long memoryBytes) {
        ElasticsearchContainer container = new ElasticsearchContainer(DockerImageName.parse("campus-market/elasticsearch:9.4.5-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:9.4.5"))
            .withEnv("xpack.security.enabled", "false").withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
            .withCreateContainerCmdModifier(DrillContainers.memoryLimit(memoryBytes)).withNetwork(network).withNetworkAliases("elasticsearch");
        container.setImage(image);
        return container;
    }

    private static GenericContainer<?> minio(Network network) {
        return new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withCommand("server /data --console-address :9001")
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin-local")
            .withNetwork(network).withNetworkAliases("minio").withExposedPorts(9000, 9001)
            .waitingFor(Wait.forListeningPort());
    }
}
