package com.example.campusmarket.supportai.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证规则语料只来自带摘要校验的公开 Markdown。 */
class PolicyCorpusTest {
    private static final Path POLICIES = locatePolicies();

    @Test
    void loadsVersionedPublicMarkdownAndSplitsOnlyAtHeadingsAndParagraphs() {
        PolicyCorpus corpus = PolicyCorpus.load(
            POLICIES.resolve("manifest.json"), POLICIES);

        assertThat(corpus.version()).isEqualTo("v1");
        assertThat(corpus.sources()).hasSize(1);
        assertThat(corpus.sources().get(0).sourceId()).isEqualTo("trade-policy");
        assertThat(corpus.sources().get(0).title()).isEqualTo("校园交易公开规则");
        assertThat(corpus.sources().get(0).version()).isEqualTo("v1");
        assertThat(corpus.chunks()).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.sourceId()).isEqualTo("trade-policy");
            assertThat(chunk.version()).isEqualTo("v1");
            assertThat(chunk.title()).isNotBlank();
            assertThat(chunk.text()).doesNotContain("订单号", "用户 ID", "邮箱", "证据");
        });
        assertThat(corpus.chunks()).extracting(PolicyChunk::title)
            .contains("退款规则", "售后争议规则");
    }

    @Test
    void rejectsTamperedSourceBeforeAnyChunkCanBeIndexed() throws Exception {
        Path manifest = POLICIES.resolve("manifest.json");
        Path source = POLICIES.resolve("trade-policy-v1.md");
        Path temp = Files.createTempDirectory("policy-corpus-");
        try {
            Files.copy(manifest, temp.resolve("manifest.json"));
            Files.writeString(temp.resolve("trade-policy-v1.md"),
                Files.readString(source) + "\n篡改内容");

            assertThatThrownBy(() -> PolicyCorpus.load(
                    temp.resolve("manifest.json"), temp))
                .isInstanceOf(PolicyCorpus.InvalidCorpusException.class);
        } finally {
            Files.deleteIfExists(temp.resolve("trade-policy-v1.md"));
            Files.deleteIfExists(temp.resolve("manifest.json"));
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void rejectsUnknownManifestFields() throws Exception {
        assertThatThrownBy(() -> loadManifestVariant(manifest ->
                manifest.replace("  \"sources\"", "  \"unexpected\": true,\n  \"sources\"")))
            .isInstanceOf(PolicyCorpus.InvalidCorpusException.class)
            .hasMessageContaining("未知");
    }

    @Test
    void rejectsMissingVisibilityInsteadOfDefaultingToPublic() throws Exception {
        assertThatThrownBy(() -> loadManifestVariant(manifest ->
                manifest.replace("      \"visibility\": \"PUBLIC\",\n", "")))
            .isInstanceOf(PolicyCorpus.InvalidCorpusException.class)
            .hasMessageContaining("visibility");
    }

    @Test
    void rejectsIllegalVisibilityInsteadOfSilentlySkippingSource() throws Exception {
        assertThatThrownBy(() -> loadManifestVariant(manifest ->
                manifest.replace("\"visibility\": \"PUBLIC\"", "\"visibility\": \"PRIVATE\"")))
            .isInstanceOf(PolicyCorpus.InvalidCorpusException.class)
            .hasMessageContaining("visibility");
    }

    private PolicyCorpus loadManifestVariant(java.util.function.UnaryOperator<String> transform)
            throws Exception {
        Path temp = Files.createTempDirectory("policy-manifest-");
        try {
            Files.copy(POLICIES.resolve("trade-policy-v1.md"),
                temp.resolve("trade-policy-v1.md"));
            Files.writeString(temp.resolve("manifest.json"),
                transform.apply(Files.readString(POLICIES.resolve("manifest.json"))));
            return PolicyCorpus.load(temp.resolve("manifest.json"), temp);
        } finally {
            Files.deleteIfExists(temp.resolve("trade-policy-v1.md"));
            Files.deleteIfExists(temp.resolve("manifest.json"));
            Files.deleteIfExists(temp);
        }
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
