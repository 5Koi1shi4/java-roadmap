package com.example.campusmarket.supportai.policy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证规则索引的版本闸门、阈值过滤与故障恢复。 */
@Testcontainers
class PolicyIndexIT {
    private static final String IMAGE =
        "docker.elastic.co/elasticsearch/elasticsearch:9.4.5";
    private static final Path POLICIES = locatePolicies();

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse(IMAGE))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
        .withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig().withMemory(768L * 1024L * 1024L));

    private static Rest5Client restClient;
    private static ElasticsearchTransport transport;
    private static ElasticsearchClient elasticsearch;
    private PolicyCorpus corpusV1;
    private ElasticsearchPolicyIndex index;
    private PolicyRetriever retriever;
    private AtomicBoolean failEmbeddings;

    @BeforeAll
    static void openClient() {
        restClient = Rest5Client.builder(
            URI.create("http://" + ELASTICSEARCH.getHttpHostAddress())).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        elasticsearch = new ElasticsearchClient(transport);
    }

    @BeforeEach
    void setUp() throws IOException {
        deletePolicyIndexes();
        corpusV1 = PolicyCorpus.load(POLICIES.resolve("manifest.json"), POLICIES);
        failEmbeddings = new AtomicBoolean();
        ElasticsearchPolicyIndex.EmbeddingProvider delegate =
            ElasticsearchPolicyIndex.deterministicEmbeddings(8);
        index = new ElasticsearchPolicyIndex(elasticsearch, text -> {
            if (failEmbeddings.get()) {
                throw new IllegalStateException("controlled embedding outage");
            }
            return delegate.embed(text);
        });
        retriever = new PolicyRetriever(corpusV1, index);
    }

    @AfterAll
    static void closeClient() throws IOException {
        if (transport != null) {
            transport.close();
        }
        if (restClient != null) {
            restClient.close();
        }
    }

    @Test
    void mismatchedIndexVersionIsUnavailable() {
        index.rebuild(corpusV1);
        PolicyCorpus corpusV2 = corpusV1.withVersion("v2");

        assertThat(index.find("退款", corpusV2.version())).isEmpty();
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.DOWN);
    }

    @Test
    void rebuildPublishesOneValidatedVersionAndFiltersLowScores() {
        index.rebuild(corpusV1);

        assertThat(index.aliasVersion()).isEqualTo(corpusV1.version());
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.UP);
        assertThat(index.indexedChunkCount()).isEqualTo(corpusV1.chunks().size());
        assertThat(retriever.find("退款")).allSatisfy(chunk -> {
            assertThat(chunk.version()).isEqualTo(corpusV1.version());
            assertThat(chunk.score()).isGreaterThanOrEqualTo(0.70d);
        });
    }

    @Test
    void failedRebuildKeepsPreviouslyValidatedAlias() {
        index.rebuild(corpusV1);
        String previousIndex = index.readIndexName();

        failEmbeddings.set(true);
        assertThat(index.rebuild(corpusV1))
            .isFalse();
        assertThat(index.readIndexName()).isEqualTo(previousIndex);
        assertThat(index.aliasVersion()).isEqualTo(corpusV1.version());
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.UP);
    }

    private static void deletePolicyIndexes() throws IOException {
        var indexes = elasticsearch.indices().get(g -> g.index("campus-policy-*"))
            .indices().keySet();
        if (indexes.isEmpty()) {
            return;
        }
        elasticsearch.indices().delete(d -> d.index(String.join(",", indexes)));
    }

    private static Path locatePolicies() {
        List<Path> candidates = List.of(Path.of("policies"), Path.of("..", "policies"));
        return candidates.stream()
            .map(Path::toAbsolutePath)
            .filter(path -> Files.exists(path.resolve("manifest.json")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("policies directory not found"));
    }
}
