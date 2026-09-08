package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.messaging.InboxRepository;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 三轮故障演练套件；每个嵌套类仅启动当前轮次需要的真实容器。 */
class RecoveryDrillIT {
    private abstract static class DrillContainers {
        protected static void start(Stream<? extends org.testcontainers.lifecycle.Startable> containers) { Startables.deepStart(containers).join(); }
        protected static void common(DynamicPropertyRegistry registry, MySQLContainer<?> mysql, GenericContainer<?> redis) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl); registry.add("spring.datasource.username", mysql::getUsername); registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
            registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false"); registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
            registry.add("campus.market.order.deadline.enabled", () -> "false"); registry.add("campus.market.search.dispatcher.enabled", () -> "false"); registry.add("campus.market.payment.reconciliation.enabled", () -> "false"); registry.add("campus.market.dispute.deadline.enabled", () -> "false"); registry.add("campus.market.dispute.return-reconciliation.enabled", () -> "false"); registry.add("campus.market.warranty.deadline.enabled", () -> "false"); registry.add("spring.task.scheduling.enabled", () -> "false");
            registry.add("spring.rabbitmq.host", () -> "127.0.0.1"); registry.add("spring.rabbitmq.port", () -> "1"); registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1"); registry.add("campus.market.storage.endpoint", () -> "http://127.0.0.1:1");
        }
    }

    @Nested
    @SpringBootTest(classes = CampusMarketApplication.class)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class RabbitRound extends RabbitContainers {
        @Autowired private JdbcTemplate jdbc; @Autowired private OutboxDispatcher outbox; @Autowired private InboxRepository inbox;
        @Test void round1RabbitDisconnectLeavesOutboxAndExpiredInboxThenRecovers() {
            UUID event=UUID.randomUUID(); insertOutbox(event,"ORDER_CREATED",UUID.randomUUID()); insertExpiredInbox(event); PROXY.setConnectionCut(true);
            try { outbox.dispatchOnce(10,Duration.ofSeconds(2)); assertThat(status("integration_outbox",event)).isIn("NEW","PUBLISHING"); } finally { PROXY.setConnectionCut(false); }
            jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE event_id=?",event.toString()); outbox.dispatchOnce(10,Duration.ofSeconds(30)); assertThat(status("integration_outbox",event)).isEqualTo("PUBLISHED");
            assertThat(inbox.process("recovery-drill",event,Duration.ofSeconds(30),claim -> {})).isTrue(); assertThat(jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name='recovery-drill' AND event_id=?",String.class,event.toString())).isEqualTo("COMPLETED"); assertInvariants();
        }
        private void insertOutbox(UUID event,String type,UUID aggregate){jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,1,1,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",event.toString(),event.toString(),type,aggregate.toString(),"{}");}
        private void insertExpiredInbox(UUID event){jdbc.update("INSERT INTO consumed_event(id,consumer_name,event_id,status,owner_id,claim_token,lease_until,attempt_count,created_at) VALUES (?,?,?,'PROCESSING','old-owner','old-token',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),1,CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),"recovery-drill",event.toString());}
        private String status(String table,UUID id){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id.toString());}
        private void assertInvariants(){assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(available_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(quarantined_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) d",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement s JOIN settlement s2 ON s.order_id=s2.order_id AND s.id<>s2.id",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_evidence e LEFT JOIN dispute_case c ON c.id=e.dispute_case_id WHERE c.id IS NULL",Integer.class)).isZero();}
    }

    @Nested
    @SpringBootTest(classes = CampusMarketApplication.class)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class SearchRound extends SearchContainers {
        @Autowired private JdbcTemplate jdbc; @Autowired private SearchOutboxDispatcher searchOutbox; @Autowired private ProductSearchPort search;
        @Test void round2ElasticsearchDisconnectLeavesSearchOutboxThenCatchesUp(){UUID seller=user(),listing=UUID.randomUUID();jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','教材',100,2,'ON_SALE',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",listing.toString(),seller.toString(),"故障演练教材");UUID event=UUID.randomUUID();jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) VALUES (?,?,1,'LISTING_PUBLISHED',CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",event.toString(),listing.toString(),"{}");PROXY.setConnectionCut(true);try{searchOutbox.dispatchOnce(10,Duration.ofSeconds(2));assertThat(status(event)).isIn("NEW","PUBLISHING");}finally{PROXY.setConnectionCut(false);}jdbc.update("UPDATE search_outbox SET available_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE id=?",event.toString());searchOutbox.dispatchOnce(10,Duration.ofSeconds(30));search.refresh();assertThat(status(event)).isEqualTo("PUBLISHED");Set<String> indexed=Set.copyOf(search.search(new ProductSearchPort.SearchRequest("故障演练教材",null,null,null,0,20)).items().stream().map(ProductSearchPort.SearchItem::listingId).toList());assertThat(indexed).contains(listing.toString());assertThat(indexed).isSubsetOf(jdbc.query("SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0",(rs,n)->rs.getString(1)));assertInvariants();}
        private UUID user(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),id+"@stu.example.edu.cn","hash");return id;} private String status(UUID id){return jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?",String.class,id.toString());}
        private void assertInvariants(){assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(available_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(quarantined_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) d",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement s JOIN settlement s2 ON s.order_id=s2.order_id AND s.id<>s2.id",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_evidence e LEFT JOIN dispute_case c ON c.id=e.dispute_case_id WHERE c.id IS NULL",Integer.class)).isZero();}
    }

    @Nested
    @SpringBootTest(classes = CampusMarketApplication.class)
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class StorageRound extends StorageContainers {
        @Autowired private JdbcTemplate jdbc; @Autowired private StorageCleanupScheduler cleanup;
        @Test void round3MinioDisconnectLeavesCleanupPendingThenDeletesAfterRecovery(){UUID submitter=user(),session=UUID.randomUUID();jdbc.update("INSERT INTO object_upload_session(id,submitted_by,purpose,object_key,status,expires_at,created_at,updated_at) VALUES (?,?, 'LISTING_MEDIA',?,'OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",session.toString(),submitter.toString(),"cleanup-drill-"+session);PROXY.setConnectionCut(true);try{cleanup.runOnce(10);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=? AND status IN ('PENDING','PROCESSING')",Integer.class,"listing-upload:"+session)).isEqualTo(1);}finally{PROXY.setConnectionCut(false);}jdbc.update("UPDATE storage_cleanup_task SET run_after=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE cleanup_business_key=?","listing-upload:"+session);cleanup.runOnce(10);assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?",String.class,"listing-upload:"+session)).isEqualTo("COMPLETED");assertInvariants();}
        private UUID user(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),id+"@stu.example.edu.cn","hash");return id;} private void assertInvariants(){assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(available_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(quarantined_quantity),0) FROM listing",Integer.class)).isGreaterThanOrEqualTo(0);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) d",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement s JOIN settlement s2 ON s.order_id=s2.order_id AND s.id<>s2.id",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_evidence e LEFT JOIN dispute_case c ON c.id=e.dispute_case_id WHERE c.id IS NULL",Integer.class)).isZero();}
    }

    private abstract static class RabbitContainers extends DrillContainers {
        protected static final Network NETWORK=Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local").withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        protected static final RabbitMQContainer RABBIT=new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13.7-management")).withNetwork(NETWORK).withNetworkAliases("rabbitmq");
        protected static final ToxiproxyContainer TOXIPROXY=new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;
        static{start(Stream.of(MYSQL,REDIS,RABBIT,TOXIPROXY));PROXY=TOXIPROXY.getProxy(RABBIT,5672);}
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r){common(r,MYSQL,REDIS);r.add("spring.rabbitmq.host",()->PROXY.getContainerIpAddress());r.add("spring.rabbitmq.port",()->PROXY.getProxyPort());r.add("spring.rabbitmq.username",RABBIT::getAdminUsername);r.add("spring.rabbitmq.password",RABBIT::getAdminPassword);}
        @AfterAll static void stop(){Stream.of(TOXIPROXY,RABBIT,MYSQL,REDIS).forEach(GenericContainer::stop);NETWORK.close();}
    }
    private abstract static class SearchContainers extends DrillContainers {
        protected static final Network NETWORK=Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local").withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        private static final ImageFromDockerfile ES_IMAGE=new ImageFromDockerfile("campus-market/elasticsearch:8.18.8-smartcn",true).withDockerfile(Path.of("docker/elasticsearch/Dockerfile"));
        protected static final ElasticsearchContainer ES=new ElasticsearchContainer(DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn").asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")).withEnv("xpack.security.enabled","false").withNetwork(NETWORK).withNetworkAliases("elasticsearch");
        protected static final ToxiproxyContainer TOXIPROXY=new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;
        static{ES.setImage(ES_IMAGE);start(Stream.of(MYSQL,REDIS,ES,TOXIPROXY));PROXY=TOXIPROXY.getProxy(ES,9200);}
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r){common(r,MYSQL,REDIS);r.add("spring.elasticsearch.uris",()->"http://"+PROXY.getContainerIpAddress()+":"+PROXY.getProxyPort());}
        @AfterAll static void stop(){Stream.of(TOXIPROXY,ES,MYSQL,REDIS).forEach(GenericContainer::stop);NETWORK.close();}
    }
    private abstract static class StorageContainers extends DrillContainers {
        protected static final Network NETWORK=Network.newNetwork();
        protected static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local").withNetwork(NETWORK).withNetworkAliases("mysql");
        protected static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
        protected static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")).withCommand("server /data --console-address :9001").withEnv("MINIO_ROOT_USER","minioadmin").withEnv("MINIO_ROOT_PASSWORD","minioadmin-local").withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000,9001).waitingFor(Wait.forListeningPort());
        protected static final ToxiproxyContainer TOXIPROXY=new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(NETWORK).withNetworkAliases("toxiproxy");
        protected static ToxiproxyContainer.ContainerProxy PROXY;
        static{start(Stream.of(MYSQL,REDIS,MINIO,TOXIPROXY));PROXY=TOXIPROXY.getProxy(MINIO,9000);}
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r){common(r,MYSQL,REDIS);r.add("campus.market.storage.endpoint",()->"http://"+PROXY.getContainerIpAddress()+":"+PROXY.getProxyPort());}
        @AfterAll static void stop(){Stream.of(TOXIPROXY,MINIO,MYSQL,REDIS).forEach(GenericContainer::stop);NETWORK.close();}
    }
}
