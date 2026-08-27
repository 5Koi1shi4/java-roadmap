package com.example.files.application.cleanup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/** 仅用于数据库不可用时的本地兜底；永不扫描正式 blobs 命名空间。 */
public final class LocalTemporaryFallbackCleaner {
    private final Path root;
    private final Duration minimumAge;
    public LocalTemporaryFallbackCleaner(Path root, Duration minimumAge) {
        if (root == null || minimumAge == null || minimumAge.isZero() || minimumAge.isNegative()) throw new IllegalArgumentException("invalid local fallback configuration");
        try {
            if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("symbolic-link storage roots are not allowed");
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException e) { throw new IllegalArgumentException("cannot initialize local fallback root", e); }
        this.minimumAge = minimumAge;
    }
    public LocalTemporaryFallbackCleaner(String root, Duration minimumAge) { this(Path.of(root), minimumAge); }
    public LocalTemporaryFallbackCleaner(com.example.files.config.FileServiceProperties properties) {
        this(Path.of(Objects.requireNonNull(properties, "properties").storage().localRoot()), properties.cleanup().temporaryObjectFallbackAge());
    }
    public int clean() {
        Path tmp = root.resolve("tmp").normalize();
        if (!tmp.startsWith(root) || Files.isSymbolicLink(tmp)) return 0;
        if (!Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) return 0;
        Instant cutoff = Instant.now().minus(minimumAge);
        int count = 0;
        try (Stream<Path> paths = Files.walk(tmp, 1)) {
            for (Path path : paths.filter(p -> !p.equals(tmp)).toList()) {
                try {
                    if (Files.isSymbolicLink(path)) continue;
                    Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!real.startsWith(root) || !real.getParent().equals(tmp)) continue;
                    BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(real); count++;
                    }
                } catch (NoSuchFileException ignored) { }
            }
            return count;
        } catch (IOException e) { throw new IllegalStateException("local temporary fallback failed", e); }
    }
    public int cleanExpired() { return clean(); }
    public int deleteExpired() { return clean(); }
}
