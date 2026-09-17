package com.example.campusmarket.supportai.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** 经人工审定的公开规则语料及其版本清单。 */
public final class PolicyCorpus {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUBLIC = "PUBLIC";
    private static final Set<String> MANIFEST_FIELDS = Set.of("version", "sources");
    private static final Set<String> SOURCE_FIELDS = Set.of(
        "sourceId", "title", "version", "path", "visibility", "sha256");

    private final String version;
    private final List<Source> sources;
    private final List<PolicyChunk> chunks;

    public PolicyCorpus(String version, List<Source> sources, List<PolicyChunk> chunks) {
        this.version = requireText(version, "规则语料版本");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "规则来源不能为空"));
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "规则片段不能为空"));
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("规则语料必须包含公开来源");
        }
        if (this.chunks.isEmpty()) {
            throw new IllegalArgumentException("规则语料必须包含规则片段");
        }
        Set<String> sourceIds = this.sources.stream().map(Source::sourceId).collect(Collectors.toSet());
        if (sourceIds.size() != this.sources.size()
                || this.chunks.stream().anyMatch(chunk -> !sourceIds.contains(chunk.sourceId()))) {
            throw new IllegalArgumentException("规则片段来源不在语料清单中");
        }
    }

    public PolicyCorpus(String version, List<PolicyChunk> chunks) {
        this(version, List.of(new Source("inline", "公开规则", version, "inline.md", "inline", true)), chunks);
    }

    public PolicyCorpus(Path manifest, Path policiesDirectory) {
        this(load(manifest, policiesDirectory));
    }

    public static PolicyCorpus load(Path policiesDirectory) {
        Objects.requireNonNull(policiesDirectory, "规则目录不能为空");
        return load(policiesDirectory.resolve("manifest.json"), policiesDirectory);
    }

    private PolicyCorpus(PolicyCorpus loaded) {
        this(loaded.version, loaded.sources, loaded.chunks);
    }

    /** 从版本清单加载并校验所有公开 Markdown 来源。 */
    public static PolicyCorpus load(Path manifest, Path policiesDirectory) {
        Objects.requireNonNull(manifest, "规则清单不能为空");
        Objects.requireNonNull(policiesDirectory, "规则目录不能为空");
        try {
            Path root = policiesDirectory.toAbsolutePath().normalize();
            JsonNode document = JSON.readTree(Files.readAllBytes(manifest));
            if (document == null || !document.isObject()) {
                throw invalid("规则清单必须是 JSON 对象");
            }
            rejectUnknownFields(document, MANIFEST_FIELDS, "规则清单");
            String corpusVersion = text(document, "version", "规则清单缺少 version");
            JsonNode sourceNodes = document.get("sources");
            if (sourceNodes == null || !sourceNodes.isArray() || sourceNodes.isEmpty()) {
                throw invalid("规则清单必须包含 sources");
            }

            List<Source> sources = new ArrayList<>();
            List<PolicyChunk> chunks = new ArrayList<>();
            Set<String> sourceIds = new HashSet<>();
            for (JsonNode sourceNode : sourceNodes) {
                if (sourceNode == null || !sourceNode.isObject()) {
                    throw invalid("规则来源必须是 JSON 对象");
                }
                rejectUnknownFields(sourceNode, SOURCE_FIELDS, "规则来源");
                JsonNode visibilityNode = sourceNode.get("visibility");
                if (visibilityNode == null || !visibilityNode.isTextual()
                        || visibilityNode.asText().isBlank()) {
                    throw invalid("规则来源 visibility 必须明确为 PUBLIC");
                }
                String visibility = visibilityNode.asText().trim();
                if (!PUBLIC.equals(visibility)) {
                    throw invalid("规则来源 visibility 非法: " + visibility);
                }
                String sourceId = text(sourceNode, "sourceId", "规则来源缺少 sourceId");
                String title = text(sourceNode, "title", "规则来源缺少 title");
                String sourceVersion = text(sourceNode, "version", "规则来源缺少 version");
                String relativePath = text(sourceNode, "path", "规则来源缺少 path");
                String expectedHash = text(sourceNode, "sha256", "规则来源缺少 sha256");
                if (!corpusVersion.equals(sourceVersion)) {
                    throw invalid("规则来源版本与清单不一致: " + sourceId);
                }
                if (!sourceIds.add(sourceId)) {
                    throw invalid("规则来源 ID 重复: " + sourceId);
                }
                Path sourcePath = root.resolve(relativePath).normalize();
                if (!sourcePath.startsWith(root) || !relativePath.endsWith(".md")) {
                    throw invalid("规则来源路径不在公开 Markdown 目录内: " + relativePath);
                }
                byte[] bytes = Files.readAllBytes(sourcePath);
                String actualHash = sha256(bytes);
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    throw invalid("规则来源摘要校验失败: " + sourceId);
                }
                Source source = new Source(sourceId, title, sourceVersion, relativePath,
                    actualHash, true);
                sources.add(source);
                chunks.addAll(split(source, new String(bytes, StandardCharsets.UTF_8)));
            }
            if (sources.isEmpty() || chunks.isEmpty()) {
                throw invalid("规则清单没有可索引的公开 Markdown");
            }
            return new PolicyCorpus(corpusVersion, sources, chunks);
        } catch (InvalidCorpusException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("读取规则语料失败", exception);
        }
    }

    /** 便于测试和离线校验的版本副本，不改变来源正文。 */
    public PolicyCorpus withVersion(String replacementVersion) {
        String newVersion = requireText(replacementVersion, "规则语料版本");
        List<Source> changedSources = sources.stream()
            .map(source -> new Source(source.sourceId(), source.title(), newVersion, source.path(),
                source.sha256(), source.publicSource()))
            .toList();
        List<PolicyChunk> changedChunks = chunks.stream()
            .map(chunk -> new PolicyChunk(chunk.sourceId(), chunk.title(), newVersion,
                chunk.text(), chunk.score()))
            .toList();
        return new PolicyCorpus(newVersion, changedSources, changedChunks);
    }

    public String version() {
        return version;
    }

    public List<Source> sources() {
        return sources;
    }

    public List<PolicyChunk> chunks() {
        return chunks;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public Optional<Source> source(String sourceId) {
        return sources.stream().filter(source -> source.sourceId().equals(sourceId)).findFirst();
    }

    public Map<String, Source> sourceMap() {
        return sources.stream().collect(Collectors.toUnmodifiableMap(Source::sourceId, source -> source));
    }

    /** 仅用于构造性测试失败的语料副本；生产流程始终使用 load 的摘要校验。 */
    public PolicyCorpus map(UnaryOperator<Source> sourceMapper) {
        Objects.requireNonNull(sourceMapper, "来源转换器不能为空");
        return new PolicyCorpus(version, sources.stream().map(sourceMapper).toList(), chunks);
    }

    private static List<PolicyChunk> split(Source source, String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\uFEFF", "");
        String[] lines = normalized.split("\\R", -1);
        List<PolicyChunk> result = new ArrayList<>();
        String currentTitle = source.title();
        StringBuilder paragraph = new StringBuilder();
        for (String line : lines) {
            String heading = heading(line);
            if (heading != null) {
                appendChunk(result, source, currentTitle, paragraph);
                currentTitle = heading;
                continue;
            }
            if (line.isBlank()) {
                appendChunk(result, source, currentTitle, paragraph);
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append('\n');
                }
                paragraph.append(line.stripTrailing());
            }
        }
        appendChunk(result, source, currentTitle, paragraph);
        return result;
    }

    private static void appendChunk(List<PolicyChunk> chunks, Source source, String title,
                                    StringBuilder paragraph) {
        String body = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!body.isEmpty()) {
            String text = title + "\n\n" + body;
            chunks.add(new PolicyChunk(source.sourceId(), title, source.version(), text, 0.0d));
        }
    }

    private static String heading(String line) {
        String trimmed = line.strip();
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes == 0 || hashes >= trimmed.length() || !Character.isWhitespace(trimmed.charAt(hashes))) {
            return null;
        }
        String title = trimmed.substring(hashes).trim().replaceFirst("\\s+#+$", "").trim();
        return title.isEmpty() ? null : title;
    }

    private static String text(JsonNode node, String field, String message) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(message);
        }
        return value.asText().trim();
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowedFields,
                                             String objectName) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw invalid(objectName + "包含未知字段: " + field);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

    private static InvalidCorpusException invalid(String message) {
        return new InvalidCorpusException(message);
    }

    private static InvalidCorpusException invalid(String message, Throwable cause) {
        return new InvalidCorpusException(message, cause);
    }

    public record Source(String sourceId, String title, String version, String path,
                         String sha256, boolean publicSource) {
        public Source {
            sourceId = requireText(sourceId, "规则来源 ID");
            title = requireText(title, "规则标题");
            version = requireText(version, "规则版本");
            path = requireText(path, "规则来源路径");
            sha256 = requireText(sha256, "规则来源摘要");
            if (!publicSource) {
                throw new IllegalArgumentException("PolicyCorpus 只允许公开来源");
            }
        }
    }

    public static class InvalidCorpusException extends RuntimeException {
        public InvalidCorpusException(String message) {
            super(message);
        }

        public InvalidCorpusException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
