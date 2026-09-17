package com.example.campusmarket.supportai.policy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 独立的公开规则向量索引；使用版本化物理索引和原子读别名切换。 */
public final class ElasticsearchPolicyIndex {
    public static final String READ_ALIAS = "campus-policy-read";
    public static final String VECTOR_FIELD = "embedding";
    public static final double MIN_SCORE = 0.70d;

    private final ElasticsearchClient client;
    private final EmbeddingProvider embeddings;
    private final String readAlias;
    private volatile Readiness readiness = Readiness.DOWN;
    private volatile boolean versionMismatch;
    private volatile String knownIndex;
    private volatile String knownVersion;
    private volatile int knownChunkCount;

    public ElasticsearchPolicyIndex(ElasticsearchClient client, EmbeddingProvider embeddings) {
        this(client, embeddings, READ_ALIAS);
    }

    public ElasticsearchPolicyIndex(ElasticsearchClient client, EmbeddingProvider embeddings,
                                    String readAlias) {
        this.client = Objects.requireNonNull(client, "Elasticsearch客户端不能为空");
        this.embeddings = Objects.requireNonNull(embeddings, "嵌入端口不能为空");
        this.readAlias = requireText(readAlias, "规则读别名");
    }

    /** 默认仅用于离线开发；生产环境应注入外部嵌入模型端口。 */
    public ElasticsearchPolicyIndex(ElasticsearchClient client) {
        this(client, deterministicEmbeddings(8));
    }

    /** 嵌入端口只暴露公开规则或查询文本，不暴露用户/交易上下文。 */
    @FunctionalInterface
    public interface EmbeddingProvider {
        List<Float> embed(String text);

        default int dimensions() {
            return -1;
        }
    }

    public enum Readiness {
        UP,
        DOWN
    }

    /** 创建固定维度、无网络访问的嵌入替身。 */
    public static EmbeddingProvider deterministicEmbeddings(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("嵌入维度必须为正数");
        }
        return new EmbeddingProvider() {
            @Override
            public List<Float> embed(String text) {
                Objects.requireNonNull(text, "嵌入文本不能为空");
                float[] vector = new float[dimensions];
                text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint))
                    .forEach(codePoint -> {
                        int first = Math.floorMod(codePoint, dimensions);
                        int second = Math.floorMod(codePoint * 31 + 17, dimensions);
                        vector[first] += 1.0f;
                        vector[second] += 0.25f;
                    });
                float norm = 0.0f;
                for (float value : vector) {
                    norm += value * value;
                }
                if (norm == 0.0f) {
                    vector[0] = 1.0f;
                    norm = 1.0f;
                }
                float scale = (float) Math.sqrt(norm);
                List<Float> result = new ArrayList<>(dimensions);
                for (float value : vector) {
                    result.add(value / scale);
                }
                return List.copyOf(result);
            }

            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    /** 适配固定维度的外部 List<Double> 嵌入客户端。 */
    public static EmbeddingProvider fromDoubleFunction(Function<String, List<Double>> function,
                                                        int dimensions) {
        Objects.requireNonNull(function, "嵌入函数不能为空");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("嵌入维度必须为正数");
        }
        return new EmbeddingProvider() {
            @Override
            public List<Float> embed(String text) {
                List<Double> values = function.apply(text);
                if (values == null || values.size() != dimensions) {
                    throw new IllegalArgumentException("外部嵌入维度不匹配");
                }
                return values.stream().map(value -> {
                    if (value == null || !Double.isFinite(value)) {
                        throw new IllegalArgumentException("外部嵌入包含无效数值");
                    }
                    return value.floatValue();
                }).toList();
            }

            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    /** 构建、校验并发布一个完整的新版本；任何失败都不改变现有读别名。 */
    public boolean rebuild(PolicyCorpus corpus) {
        Objects.requireNonNull(corpus, "规则语料不能为空");
        String previousIndex = readIndexName();
        String newIndex = indexName(corpus.version());
        try {
            int dimensions = resolveDimensions(corpus);
            createIndex(newIndex, dimensions);
            for (int i = 0; i < corpus.chunks().size(); i++) {
                indexChunk(newIndex, corpus, i, corpus.chunks().get(i), dimensions);
            }
            client.indices().refresh(refresh -> refresh.index(newIndex));
            validateIndex(newIndex, corpus);
            replaceReadAlias(newIndex);
            knownIndex = newIndex;
            knownVersion = corpus.version();
            knownChunkCount = corpus.chunks().size();
            versionMismatch = false;
            readiness = Readiness.UP;
            if (previousIndex != null && !previousIndex.equals(newIndex)) {
                deleteIndex(previousIndex);
            }
            return true;
        } catch (Exception failure) {
            deleteIndexQuietly(newIndex);
            knownIndex = previousIndex;
            if (previousIndex == null) {
                knownVersion = null;
                knownChunkCount = 0;
                readiness = Readiness.DOWN;
            } else {
                readiness = versionMismatch ? Readiness.DOWN : Readiness.UP;
            }
            return false;
        }
    }

    /** 当前读别名的版本不符合请求版本时返回空结果并拉低就绪状态。 */
    public List<PolicyChunk> find(String question, String expectedVersion) {
        Objects.requireNonNull(expectedVersion, "期望规则版本不能为空");
        String actualVersion = aliasVersion();
        if (!expectedVersion.equals(actualVersion)) {
            versionMismatch = true;
            readiness = Readiness.DOWN;
            return List.of();
        }
        return similaritySearch(question, 5);
    }

    /** 在当前独立读别名中执行 kNN 检索。 */
    public List<PolicyChunk> similaritySearch(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        String index = readIndexName();
        if (index == null) {
            readiness = Readiness.DOWN;
            throw new PolicyUnavailableException("规则索引尚未就绪");
        }
        try {
            List<Float> vector = vector(embeddings.embed(question));
            SearchResponse<Map> response = client.search(search -> search
                    .index(readAlias)
                    .knn(knn -> knn.field(VECTOR_FIELD).queryVector(vector)
                        .k(limit).numCandidates(Math.max(limit * 4, 20)))
                    .size(limit), Map.class);
            List<PolicyChunk> result = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map source = hit.source();
                if (source == null) {
                    continue;
                }
                String version = text(source.get("corpusVersion"));
                String scoreVersion = version;
                double score = hit.score() == null ? 0.0d : hit.score();
                result.add(new PolicyChunk(text(source.get("sourceId")), text(source.get("title")),
                    scoreVersion, text(source.get("text")), score));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException failure) {
            readiness = Readiness.DOWN;
            if (failure instanceof PolicyUnavailableException unavailable) {
                throw unavailable;
            }
            throw new PolicyUnavailableException("规则索引检索失败", failure);
        }
    }

    public String aliasVersion() {
        String index = readIndexName();
        if (index == null) {
            knownVersion = null;
            knownChunkCount = 0;
            readiness = Readiness.DOWN;
            return null;
        }
        if (index.equals(knownIndex) && knownVersion != null) {
            return knownVersion;
        }
        try {
            SearchResponse<Map> response = client.search(search -> search.index(index)
                .size(1), Map.class);
            if (response.hits().hits().isEmpty() || response.hits().hits().get(0).source() == null) {
                readiness = Readiness.DOWN;
                return null;
            }
            Map source = response.hits().hits().get(0).source();
            knownIndex = index;
            knownVersion = text(source.get("corpusVersion"));
            knownChunkCount = indexedChunkCount(index);
            if (!versionMismatch) {
                readiness = Readiness.UP;
            }
            return knownVersion;
        } catch (IOException | RuntimeException failure) {
            readiness = Readiness.DOWN;
            return null;
        }
    }

    public Readiness readiness() {
        if (versionMismatch) {
            return Readiness.DOWN;
        }
        if (readiness == Readiness.DOWN) {
            aliasVersion();
        }
        return readiness;
    }

    public String readIndexName() {
        try {
            Set<String> members = aliasMembers();
            if (members.size() != 1) {
                return null;
            }
            return members.iterator().next();
        } catch (IOException failure) {
            throw new PolicyUnavailableException("读取规则索引别名失败", failure);
        } catch (ElasticsearchException missing) {
            if (missing.status() == 404) {
                return null;
            }
            throw new PolicyUnavailableException("读取规则索引别名失败", missing);
        }
    }

    public int indexedChunkCount() {
        String index = readIndexName();
        if (index == null) {
            return 0;
        }
        if (index.equals(knownIndex) && knownChunkCount > 0) {
            return knownChunkCount;
        }
        try {
            return indexedChunkCount(index);
        } catch (IOException failure) {
            throw new PolicyUnavailableException("读取规则索引段数失败", failure);
        }
    }

    private int resolveDimensions(PolicyCorpus corpus) {
        int dimensions = embeddings.dimensions();
        if (dimensions > 0) {
            return dimensions;
        }
        List<Float> sample = vector(embeddings.embed(corpus.chunks().get(0).text()));
        return sample.size();
    }

    private void createIndex(String index, int dimensions) throws IOException {
        client.indices().create(create -> create.index(index)
            .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
            .mappings(mapping -> mapping
                .properties("corpusVersion", p -> p.keyword(k -> k))
                .properties("sourceId", p -> p.keyword(k -> k))
                .properties("title", p -> p.keyword(k -> k))
                .properties("version", p -> p.keyword(k -> k))
                .properties("text", p -> p.text(t -> t.index(false)))
                .properties("chunkId", p -> p.keyword(k -> k))
                .properties("sourceHash", p -> p.keyword(k -> k))
                .properties("chunkHash", p -> p.keyword(k -> k))
                .properties(VECTOR_FIELD, p -> p.denseVector(vector -> vector
                    .dims(dimensions).index(true).similarity(DenseVectorSimilarity.Cosine)))));
    }

    private void indexChunk(String index, PolicyCorpus corpus, int number, PolicyChunk chunk,
                            int dimensions) throws IOException {
        List<Float> embedding = vector(embeddings.embed(chunk.text()));
        if (embedding.size() != dimensions) {
            throw new IllegalArgumentException("嵌入模型返回了不一致的维度");
        }
        PolicyCorpus.Source source = corpus.source(chunk.sourceId())
            .orElseThrow(() -> new IllegalArgumentException("规则来源不存在: " + chunk.sourceId()));
        Map<String, Object> document = new HashMap<>();
        document.put("corpusVersion", corpus.version());
        document.put("sourceId", chunk.sourceId());
        document.put("title", chunk.title());
        document.put("version", chunk.version());
        document.put("text", chunk.text());
        document.put("chunkId", Integer.toString(number));
        document.put("sourceHash", source.sha256());
        document.put("chunkHash", sha256(chunk.text().getBytes(StandardCharsets.UTF_8)));
        document.put(VECTOR_FIELD, embedding);
        client.index(request -> request.index(index).id(chunkId(chunk, number)).document(document));
    }

    private void validateIndex(String index, PolicyCorpus corpus) throws IOException {
        SearchResponse<Map> response = client.search(search -> search.index(index)
            .size(Math.max(1, corpus.chunks().size() + 1)), Map.class);
        if (response.hits().hits().size() != corpus.chunks().size()) {
            throw new IllegalStateException("规则索引段数校验失败");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map source = hit.source();
            if (source == null) {
                throw new IllegalStateException("规则索引正文缺失");
            }
            String id = text(source.get("chunkId"));
            ids.add(id);
            int number = Integer.parseInt(id);
            if (number < 0 || number >= corpus.chunks().size()) {
                throw new IllegalStateException("规则索引段编号无效");
            }
            PolicyChunk expected = corpus.chunks().get(number);
            PolicyCorpus.Source expectedSource = corpus.source(expected.sourceId()).orElseThrow();
            if (!corpus.version().equals(text(source.get("corpusVersion")))
                    || !expected.sourceId().equals(text(source.get("sourceId")))
                    || !expected.title().equals(text(source.get("title")))
                    || !expected.version().equals(text(source.get("version")))
                    || !expected.text().equals(text(source.get("text")))
                    || !expectedSource.sha256().equals(text(source.get("sourceHash")))
                    || !sha256(expected.text().getBytes(StandardCharsets.UTF_8))
                        .equals(text(source.get("chunkHash")))) {
                throw new IllegalStateException("规则索引内容摘要校验失败");
            }
        }
        if (ids.size() != corpus.chunks().size()) {
            throw new IllegalStateException("规则索引段编号重复");
        }
    }

    private void replaceReadAlias(String newIndex) throws IOException {
        Set<String> current = aliasMembers();
        client.indices().updateAliases(update -> {
            for (String oldIndex : current) {
                update.actions(action -> action.remove(remove -> remove.index(oldIndex).alias(readAlias)));
            }
            update.actions(action -> action.add(add -> add.index(newIndex).alias(readAlias)));
            return update;
        });
    }

    private Set<String> aliasMembers() throws IOException {
        try {
            return new LinkedHashSet<>(client.indices().getAlias(alias -> alias.name(readAlias))
                .aliases().keySet());
        } catch (ElasticsearchException missing) {
            if (missing.status() == 404) {
                return new LinkedHashSet<>();
            }
            throw missing;
        }
    }

    private int indexedChunkCount(String index) throws IOException {
        return Math.toIntExact(client.count(count -> count.index(index)).count());
    }

    private void deleteIndex(String index) {
        if (index == null || index.isBlank()) {
            return;
        }
        try {
            client.indices().delete(delete -> delete.index(index));
        } catch (IOException | ElasticsearchException ignored) {
            // 清理失败不应影响已经验证的读别名。
        }
    }

    private void deleteIndexQuietly(String index) {
        if (index != null && !index.equals(knownIndex)) {
            deleteIndex(index);
        }
    }

    private static String indexName(String version) {
        String safeVersion = version.toLowerCase().replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        if (safeVersion.isBlank()) {
            safeVersion = "version";
        }
        return "campus-policy-" + safeVersion + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String chunkId(PolicyChunk chunk, int number) {
        return Integer.toString(number);
    }

    private static List<Float> vector(List<Float> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("嵌入模型返回空向量");
        }
        List<Float> copy = new ArrayList<>(values.size());
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("嵌入模型返回无效向量");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("规则索引字段缺失");
        }
        return value.toString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    public static final class PolicyUnavailableException extends RuntimeException {
        public PolicyUnavailableException(String message) {
            super(message);
        }

        public PolicyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
