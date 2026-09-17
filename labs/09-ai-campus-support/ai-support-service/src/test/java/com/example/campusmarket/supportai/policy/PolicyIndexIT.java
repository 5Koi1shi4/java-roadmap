package com.example.campusmarket.supportai.policy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.elasticsearch.core.SearchResponse;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        PolicyRetriever mismatchedRetriever = new PolicyRetriever(corpusV2, index);
        assertThatThrownBy(() -> mismatchedRetriever.find("退款"))
            .isInstanceOf(PolicyRetriever.PolicyUnavailableException.class);
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.DOWN);

        assertThat(index.find("退款", corpusV2.version())).isEmpty();
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.DOWN);

        assertThat(index.find("退款", corpusV1.version())).isNotNull();
        assertThat(index.readiness()).isEqualTo(ElasticsearchPolicyIndex.Readiness.UP);
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
    void lowScoringEsCandidateIsReturnedThenFilteredAndResultsAreCapped() throws IOException {
        index = new ElasticsearchPolicyIndex(elasticsearch,
            PolicyIndexIT::thresholdTestEmbedding);
        retriever = new PolicyRetriever(corpusV1, index);
        assertThat(index.rebuild(corpusV1)).isTrue();

        List<Float> queryVector = thresholdTestEmbedding("threshold-question");
        SearchResponse<Map> rawResponse = elasticsearch.search(search -> search
            .index(index.readIndexName())
            .knn(knn -> knn.field(ElasticsearchPolicyIndex.VECTOR_FIELD)
                .queryVector(queryVector)
                .k(corpusV1.chunkCount())
                .numCandidates(corpusV1.chunkCount() * 4))
            .size(corpusV1.chunkCount()), Map.class);
        assertThat(rawResponse.hits().hits())
            .anySatisfy(hit -> assertThat(hit.score()).isGreaterThanOrEqualTo(0.70d))
            .anySatisfy(hit -> assertThat(hit.score()).isLessThan(0.70d));

        assertThat(index.similaritySearch("threshold-question", 5))
            .isNotEmpty()
            .hasSize(4)
            .allSatisfy(chunk -> assertThat(chunk.score()).isGreaterThanOrEqualTo(0.70d));
        assertThatThrownBy(() -> index.similaritySearch("threshold-question", 6))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(retriever.find("threshold-question"))
            .isNotEmpty()
            .hasSize(4)
            .allSatisfy(chunk -> assertThat(chunk.score()).isGreaterThanOrEqualTo(0.70d));
    }

    @Test
    void retrieverCannotRelaxPublicSafetyBounds() {
        assertThatThrownBy(() -> new PolicyRetriever(corpusV1, index, 0.69d, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyRetriever(corpusV1, index, 0.70d, 6))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aliasVersionDoesNotTrustCachedDocumentVersion() throws IOException {
        PolicyCorpus singleChunk = new PolicyCorpus("v1", List.of(
            new PolicyChunk("inline", "单条规则", "v1", "单条公开规则正文", 0.0d)));
        ElasticsearchPolicyIndex singleIndex = new ElasticsearchPolicyIndex(elasticsearch,
            text -> fixedVector(1.0f, 0.0f));
        assertThat(singleIndex.rebuild(singleChunk)).isTrue();
        String physicalIndex = singleIndex.readIndexName();
        elasticsearch.update(update -> update.index(physicalIndex).id("0")
            .doc(Map.of("corpusVersion", "v2")), Map.class);
        elasticsearch.indices().refresh(refresh -> refresh.index(physicalIndex));

        assertThat(singleIndex.aliasVersion()).isEqualTo("v2");
    }

    @Test
    void directIndexRejectsDocumentWithStaleVersion() throws IOException {
        PolicyCorpus singleChunk = new PolicyCorpus("v1", List.of(
            new PolicyChunk("inline", "单条规则", "v1", "单条公开规则正文", 0.0d)));
        ElasticsearchPolicyIndex singleIndex = new ElasticsearchPolicyIndex(elasticsearch,
            text -> fixedVector(1.0f, 0.0f));
        assertThat(singleIndex.rebuild(singleChunk)).isTrue();
        String physicalIndex = singleIndex.readIndexName();
        elasticsearch.update(update -> update.index(physicalIndex).id("0")
            .doc(Map.of("version", "v2")), Map.class);
        elasticsearch.indices().refresh(refresh -> refresh.index(physicalIndex));

        assertThat(singleIndex.similaritySearch("version-question", 5)).isEmpty();
    }

    @Test
    void directIndexRejectsPrivateDocument() throws IOException {
        PolicyCorpus singleChunk = new PolicyCorpus("v1", List.of(
            new PolicyChunk("inline", "单条规则", "v1", "单条公开规则正文", 0.0d)));
        ElasticsearchPolicyIndex singleIndex = new ElasticsearchPolicyIndex(elasticsearch,
            text -> fixedVector(1.0f, 0.0f));
        assertThat(singleIndex.rebuild(singleChunk)).isTrue();
        String physicalIndex = singleIndex.readIndexName();
        elasticsearch.update(update -> update.index(physicalIndex).id("0")
            .doc(Map.of("visibility", "PRIVATE")), Map.class);
        elasticsearch.indices().refresh(refresh -> refresh.index(physicalIndex));

        assertThat(singleIndex.similaritySearch("visibility-question", 5)).isEmpty();
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

    @Test
    void failedValidationAfterGenerationWasWrittenKeepsPreviouslyValidatedAlias()
            throws IOException {
        index = new ElasticsearchPolicyIndex(elasticsearch,
            PolicyIndexIT::thresholdTestEmbedding);
        PolicyRetriever previousRetriever = new PolicyRetriever(corpusV1, index);
        assertThat(index.rebuild(corpusV1)).isTrue();
        String previousIndex = index.readIndexName();
        assertThat(previousRetriever.find("threshold-question"))
            .isNotEmpty()
            .allSatisfy(chunk -> assertThat(chunk.version()).isEqualTo(corpusV1.version()));

        PolicyCorpus corpusV2 = corpusV1.withVersion("v2");

        assertThat(index.rebuild(corpusV2, newIndex -> {
            elasticsearch.delete(delete -> delete.index(newIndex).id("0"));
            elasticsearch.indices().refresh(refresh -> refresh.index(newIndex));
        }))
            .isFalse();
        assertThat(index.readIndexName()).isEqualTo(previousIndex);
        assertThat(index.aliasVersion()).isEqualTo(corpusV1.version());
        List<PolicyChunk> retained = previousRetriever.find("threshold-question");
        assertThat(retained)
            .isNotEmpty()
            .hasSize(4)
            .allSatisfy(chunk -> {
                assertThat(chunk.version()).isEqualTo("v1");
                assertThat(chunk.text()).doesNotContain("v2");
            });
        assertThat(retained).extracting(PolicyChunk::title).contains("退款规则");
    }

    private static List<Float> thresholdTestEmbedding(String text) {
        if ("threshold-question".equals(text)
            || text.startsWith("校园交易公开规则")
            || text.startsWith("退款规则")
            || text.startsWith("售后争议规则\n\n买家收到商品")) {
            return fixedVector(1.0f, 0.0f);
        }
        return fixedVector(-1.0f, 0.0f);
    }

    private static List<Float> fixedVector(float first, float second) {
        return List.of(first, second, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
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
