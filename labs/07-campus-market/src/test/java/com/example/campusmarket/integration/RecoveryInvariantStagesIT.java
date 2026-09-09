package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.example.campusmarket.storage.PrivateObjectStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 每个阶段独立 JVM/容器组运行，避免 Rabbit、MinIO、Elasticsearch 重叠。 */
class RecoveryInvariantStagesIT {
    private abstract static class Base {
        static void common(DynamicPropertyRegistry registry, MySQLContainer<?> mysql) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:1");
            registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
            registry.add("spring.rabbitmq.port", () -> "1");
            registry.add("campus.market.order.deadline.enabled", () -> "false");
            registry.add("campus.market.search.dispatcher.enabled", () -> "false");
            registry.add("spring.task.scheduling.enabled", () -> "false");
        }

        static void seedSale(JdbcTemplate jdbc) {
            UUID seller = user(jdbc), listing = UUID.randomUUID();
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?, '阶段商品','恢复清单','教材',100,1,'ON_SALE',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                listing.toString(), seller.toString());
            jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) VALUES (?,?,1,'LISTING_PUBLISHED',CAST('{}' AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), listing.toString());
        }

        static UUID user(JdbcTemplate jdbc) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id.toString(), id + "@stu.example.edu.cn", "hash");
            return id;
        }

        static void sqlInvariants(JdbcTemplate jdbc) {
            assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(available_quantity),0) FROM listing", Integer.class)).isGreaterThanOrEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) d", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT order_id FROM settlement GROUP BY order_id HAVING COUNT(*)>1) s", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class)).isZero();
        }
    }

    private abstract static class AclStage extends Base {
        static final Network NETWORK = Network.newNetwork();
        static final MySQLContainer<?> MYSQL = mysql(NETWORK);
        static final GenericContainer<?> MINIO = minio(NETWORK);
        static { Startables.deepStart(Stream.of(MYSQL, MINIO)).join(); }
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
            common(r, MYSQL);
            r.add("campus.market.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
            r.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
        }
        @AfterAll static void stop() { MINIO.stop(); MYSQL.stop(); NETWORK.close(); }

        void verifyAcl(JdbcTemplate jdbc, PrivateObjectStorage storage, JwtService jwt, int port) throws Exception {
            UUID buyer=user(jdbc), seller=user(jdbc), other=user(jdbc), admin=user(jdbc), listing=UUID.randomUUID(), order=UUID.randomUUID(), dispute=UUID.randomUUID(), evidence=UUID.randomUUID();
            String key="recovery-acl/"+evidence; byte[] body="proof".getBytes(StandardCharsets.UTF_8);
            storage.put(key,new ByteArrayInputStream(body),body.length,"image/png");
            jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?, 'ACL','描述','教材',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",listing.toString(),seller.toString());
            jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, 'ACL','描述',100,1,100,100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",order.toString(),buyer.toString(),seller.toString(),listing.toString());
            jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",dispute.toString(),order.toString(),buyer.toString());
            jdbc.update("INSERT INTO dispute_evidence(id,dispute_case_id,submitted_by,object_key,media_type,size_bytes,created_at) VALUES (?,?,?,?, 'image/png',?,CURRENT_TIMESTAMP(6))",evidence.toString(),dispute.toString(),buyer.toString(),key,body.length);
            String path="/api/disputes/"+dispute+"/evidence/"+evidence+"/content";
            assertThat(get(port,path,jwt.issue(new AuthenticatedUser(buyer,Set.of("ROLE_USER"))))).isEqualTo(200);
            assertThat(get(port,path,jwt.issue(new AuthenticatedUser(seller,Set.of("ROLE_USER"))))).isEqualTo(200);
            assertThat(get(port,path,jwt.issue(new AuthenticatedUser(other,Set.of("ROLE_USER"))))).isEqualTo(404);
            assertThat(get(port,path,jwt.issue(new AuthenticatedUser(admin,Set.of("ROLE_ADMIN"))))).isEqualTo(404);
            sqlInvariants(jdbc);
        }
        private int get(int port,String path,String token)throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    }

    private abstract static class SearchStage extends Base {
        static final Network NETWORK=Network.newNetwork(); static final MySQLContainer<?> MYSQL=mysql(NETWORK);
        static final ImageFromDockerfile IMAGE=new ImageFromDockerfile("campus-market/elasticsearch:8.18.8-smartcn",true).withDockerfile(Path.of("docker/elasticsearch/Dockerfile"));
        static final ElasticsearchContainer ES=es(NETWORK,IMAGE);
        static {Startables.deepStart(Stream.of(MYSQL,ES)).join();}
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r){common(r,MYSQL);r.add("spring.elasticsearch.uris",()->"http://"+ES.getHost()+":"+ES.getMappedPort(9200));r.add("campus.market.storage.endpoint",()->"http://127.0.0.1:1");}
        @AfterAll static void stop(){ES.stop();MYSQL.stop();NETWORK.close();}
        void verifySearch(JdbcTemplate jdbc,SearchOutboxDispatcher dispatcher,ProductSearchPort search){seedSale(jdbc);assertThat(dispatcher.dispatchOnce(20,Duration.ofSeconds(30))).isEqualTo(1);search.refresh();Set<String> actual=Set.copyOf(search.search(new ProductSearchPort.SearchRequest(null,null,null,null,0,100)).items().stream().map(ProductSearchPort.SearchItem::listingId).toList());Set<String> expected=Set.copyOf(jdbc.query("SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0",(rs,n)->rs.getString(1)));assertThat(actual).isEqualTo(expected);sqlInvariants(jdbc);}
    }

    @Nested @SpringBootTest(classes=CampusMarketApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("local") @DirtiesContext class RabbitAcl extends AclStage {@Autowired JdbcTemplate jdbc;@Autowired PrivateObjectStorage storage;@Autowired JwtService jwt;@LocalServerPort int port;@Test void rabbitRoundAcl() throws Exception{verifyAcl(jdbc,storage,jwt,port);}}
    @Nested @SpringBootTest(classes=CampusMarketApplication.class) @ActiveProfiles("local") @DirtiesContext class RabbitSearch extends SearchStage {@Autowired JdbcTemplate jdbc;@Autowired SearchOutboxDispatcher dispatcher;@Autowired ProductSearchPort search;@Test void rabbitRoundSearch(){verifySearch(jdbc,dispatcher,search);}}
    @Nested @SpringBootTest(classes=CampusMarketApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("local") @DirtiesContext class SearchAcl extends AclStage {@Autowired JdbcTemplate jdbc;@Autowired PrivateObjectStorage storage;@Autowired JwtService jwt;@LocalServerPort int port;@Test void searchRoundAcl() throws Exception{verifyAcl(jdbc,storage,jwt,port);}}
    @Nested @SpringBootTest(classes=CampusMarketApplication.class) @ActiveProfiles("local") @DirtiesContext class SearchSearch extends SearchStage {@Autowired JdbcTemplate jdbc;@Autowired SearchOutboxDispatcher dispatcher;@Autowired ProductSearchPort search;@Test void searchRoundSearch(){verifySearch(jdbc,dispatcher,search);}}
    @Nested @SpringBootTest(classes=CampusMarketApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("local") @DirtiesContext class StorageAcl extends AclStage {@Autowired JdbcTemplate jdbc;@Autowired PrivateObjectStorage storage;@Autowired JwtService jwt;@LocalServerPort int port;@Test void storageRoundAcl() throws Exception{verifyAcl(jdbc,storage,jwt,port);}}
    @Nested @SpringBootTest(classes=CampusMarketApplication.class) @ActiveProfiles("local") @DirtiesContext class StorageSearch extends SearchStage {@Autowired JdbcTemplate jdbc;@Autowired SearchOutboxDispatcher dispatcher;@Autowired ProductSearchPort search;@Test void storageRoundSearch(){verifySearch(jdbc,dispatcher,search);}}

    private static MySQLContainer<?> mysql(Network n){return new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("campus_market").withUsername("campus_market").withPassword("campus_market_local").withNetwork(n).withCreateContainerCmdModifier(c->c.getHostConfig().withMemory(512L*1024*1024));}
    private static GenericContainer<?> minio(Network n){return new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")).withCommand("server /data --console-address :9001").withEnv("MINIO_ROOT_USER","minioadmin").withEnv("MINIO_ROOT_PASSWORD","minioadmin-local").withNetwork(n).withExposedPorts(9000).waitingFor(Wait.forListeningPort());}
    private static ElasticsearchContainer es(Network n,ImageFromDockerfile image){ElasticsearchContainer c=new ElasticsearchContainer(DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn").asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")).withEnv("xpack.security.enabled","false").withEnv("ES_JAVA_OPTS","-Xms128m -Xmx192m").withNetwork(n).withCreateContainerCmdModifier(x->x.getHostConfig().withMemory(768L*1024*1024));c.setImage(image);return c;}
}
