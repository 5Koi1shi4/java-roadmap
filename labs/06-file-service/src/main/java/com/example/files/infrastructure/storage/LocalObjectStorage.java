package com.example.files.infrastructure.storage;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.audit.FileServiceMetrics;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Filesystem-backed object storage constrained to opaque temporary/blob UUID keys. */
public final class LocalObjectStorage implements ObjectStorage {
    private static final Pattern KEY = Pattern.compile("(?:tmp|blobs)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final Path root;
    private final FileServiceMetrics metrics;

    public LocalObjectStorage(Path root) {
        this(root, null);
    }

    public LocalObjectStorage(Path root, FileServiceMetrics metrics) {
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
        this.metrics = metrics;
    }

    public LocalObjectStorage(String root) {
        this(Path.of(root));
    }

    public LocalObjectStorage(String root, FileServiceMetrics metrics) {
        this(Path.of(root), metrics);
    }

    public LocalObjectStorage(FileServiceProperties.Storage storage) {
        this(java.util.Objects.requireNonNull(storage, "storage").localRoot());
    }

    public LocalObjectStorage(FileServiceProperties.Storage storage, FileServiceMetrics metrics) {
        this(java.util.Objects.requireNonNull(storage, "storage").localRoot(), metrics);
    }

    @Override
    public TemporaryObject writeTemporary(String tempKey, InputStream source, long maxBytes) {
        long started = System.nanoTime();
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        Path target = resolveInsideRoot(tempKey);
        ensureNamespace(tempKey, "tmp");
        try {
            Files.createDirectories(target.getParent());
            ensureNoSymlink(target.getParent());
            ensureNoSymlink(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("temporary object already exists");
            }
            long count = 0;
            try (InputStream in = source;
                 var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
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
            BasicFileAttributes writtenAttributes = Files.readAttributes(target,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!writtenAttributes.isRegularFile()) {
                throw new IllegalArgumentException("temporary object must be a regular file");
            }
            observe("write_temporary", "success", started);
            return new TemporaryObject(tempKey, count);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write temporary object", ex);
        }
    }

    @Override
    public void commit(String tempKey, String objectKey) {
        long started = System.nanoTime();
        Path source = resolveInsideRoot(tempKey);
        Path target = resolveInsideRoot(objectKey);
        ensureNamespace(tempKey, "tmp");
        ensureNamespace(objectKey, "blobs");
        try {
            ensureNoSymlink(source);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("temporary object does not exist");
            }
            BasicFileAttributes sourceAttributes = Files.readAttributes(source,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sourceAttributes.isRegularFile()) {
                throw new IllegalArgumentException("temporary object must be a regular file");
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
            ensureNoSymlink(target);
            BasicFileAttributes committedAttributes = Files.readAttributes(target,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!committedAttributes.isRegularFile()) {
                throw new IllegalArgumentException("committed object must be a regular file");
            }
            observe("commit", "success", started);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot commit temporary object", ex);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        long started = System.nanoTime();
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        try {
            ensureNoSymlink(path);
            InputStream input = Files.newInputStream(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                input.close();
                throw new IllegalArgumentException("object must be a regular file");
            }
            ensureNoSymlink(path);
            observe("open", "success", started);
            return input;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot open object", ex);
        }
    }

    @Override
    public StorageObjectMetadata stat(String objectKey) {
        long started = System.nanoTime();
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        try {
            ensureNoSymlink(path);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IllegalArgumentException("object must be a regular file");
            StorageObjectMetadata metadata = new StorageObjectMetadata(attributes.size());
            observe("stat", "success", started);
            return metadata;
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new StorageObjectNotFoundException("object does not exist");
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot stat object", ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        long started = System.nanoTime();
        Path path = resolveInsideRoot(objectKey);
        ensureNamespace(objectKey, namespace(objectKey));
        try {
            ensureNoSymlink(path);
            Files.deleteIfExists(path);
            observe("delete", "success", started);
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
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0
            || ttl.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("link ttl must be positive and no more than 2 minutes");
        }
        observe("presign", "success", System.nanoTime());
        return Optional.empty();
    }

    /** Resolves a key and rejects traversal before any filesystem operation. */
    public Path resolveInsideRoot(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("storage key must not be blank");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("storage key escapes configured root");
        }
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("storage key must use tmp/<UUID> or blobs/<UUID>");
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
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("symbolic links are not allowed in storage paths");
                }
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Path real = current.toRealPath();
                    if (!real.startsWith(root)) {
                        throw new IllegalArgumentException("storage path resolves outside configured root");
                    }
                }
            } catch (SecurityException ex) {
                throw new IllegalArgumentException("cannot inspect storage path", ex);
            } catch (IOException ex) {
                throw new IllegalArgumentException("cannot inspect storage path", ex);
            }
            if (current.equals(root)) break;
            current = current.getParent();
        }
    }

    private void observe(String operation, String result, long started) {
        if (metrics == null) return;
        try { metrics.recordStorageOperation(operation, result, Duration.ofNanos(System.nanoTime() - started)); }
        catch (RuntimeException ignored) { }
    }
}
