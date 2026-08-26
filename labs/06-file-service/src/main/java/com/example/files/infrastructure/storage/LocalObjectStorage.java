package com.example.files.infrastructure.storage;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.config.FileServiceProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Filesystem-backed object storage constrained to opaque temporary/blob UUID keys. */
public final class LocalObjectStorage implements ObjectStorage {
    private static final Pattern KEY = Pattern.compile("(?:tmp|blobs)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final Path root;

    public LocalObjectStorage(Path root) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("symbolic-link storage roots are not allowed");
        }
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot initialize local storage root", ex);
        }
    }

    public LocalObjectStorage(String root) {
        this(Path.of(root));
    }

    public LocalObjectStorage(FileServiceProperties.Storage storage) {
        this(java.util.Objects.requireNonNull(storage, "storage").localRoot());
    }

    @Override
    public TemporaryObject writeTemporary(String tempKey, InputStream source, long maxBytes) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        Path target = resolveInsideRoot(tempKey);
        ensureNamespace(tempKey, "tmp");
        try {
            Files.createDirectories(target.getParent());
            ensureNoSymlink(target.getParent());
            ensureNoSymlink(target);
            long count = 0;
            try (InputStream in = source;
                 var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    if (count > maxBytes - n) {
                        throw new com.example.files.application.upload.UploadRejectedException(
                            "FILE_TOO_LARGE", "upload exceeds byte limit");
                    }
                    out.write(buffer, 0, n);
                    count += n;
                }
            } catch (RuntimeException | IOException ex) {
                Files.deleteIfExists(target);
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new UncheckedIOException((IOException) ex);
            }
            return new TemporaryObject(tempKey, count);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write temporary object", ex);
        }
    }

    @Override
    public void commit(String tempKey, String objectKey) {
        Path source = resolveInsideRoot(tempKey);
        Path target = resolveInsideRoot(objectKey);
        ensureNamespace(tempKey, "tmp");
        ensureNamespace(objectKey, "blobs");
        try {
            ensureNoSymlink(source);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("temporary object does not exist");
            }
            Files.createDirectories(target.getParent());
            ensureNoSymlink(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                Files.move(source, target);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot commit temporary object", ex);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        try {
            ensureNoSymlink(path);
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot open object", ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        try {
            ensureNoSymlink(path);
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot delete object", ex);
        }
    }

    /** Convenience probe for cleanup and integration checks; still applies key validation. */
    public boolean exists(String objectKey) {
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        ensureNoSymlink(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Optional<URI> createPresignedGet(String objectKey, Duration ttl,
                                            Map<String, String> responseHeaders) {
        resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, "blobs");
        return Optional.empty();
    }

    /** Resolves a key and rejects traversal before any filesystem operation. */
    public Path resolveInsideRoot(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("storage key must not be blank");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("storage key escapes configured root");
        }
        return resolved;
    }

    private void ensureNamespace(String key, String expected) {
        if (!KEY.matcher(key).matches() || !key.startsWith(expected + "/")) {
            throw new IllegalArgumentException("storage key must be " + expected + "/<UUID>");
        }
        try {
            UUID.fromString(key.substring(expected.length() + 1));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("storage key must contain a UUID", ex);
        }
    }

    private static String namespace(String key) {
        if (key != null && key.startsWith("tmp/")) return "tmp";
        if (key != null && key.startsWith("blobs/")) return "blobs";
        throw new IllegalArgumentException("storage key must use a supported namespace");
    }

    private void ensureNoSymlink(Path path) {
        Path current = path;
        while (current != null && current.startsWith(root)) {
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("symbolic links are not allowed in storage paths");
                }
            } catch (SecurityException ex) {
                throw new IllegalArgumentException("cannot inspect storage path", ex);
            }
            if (current.equals(root)) break;
            current = current.getParent();
        }
    }
}
