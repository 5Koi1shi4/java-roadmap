package com.example.files.application.upload;

import com.example.files.config.FileServiceProperties;
import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import org.springframework.util.unit.DataSize;

/** Detects a bounded prefix and exposes the source body through one digesting stream. */
public final class UploadInspector {
    public static final long DEFAULT_MAX_BYTES = 20L * 1024L * 1024L;
    private static final int DETECTION_PREFIX_BYTES = 8 * 1024;
    private final long defaultMaxBytes;

    public UploadInspector() {
        this(DEFAULT_MAX_BYTES);
    }

    public UploadInspector(long maxBytes) {
        if (maxBytes <= 0 || maxBytes > DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 byte and 20 MiB");
        }
        this.defaultMaxBytes = maxBytes;
    }

    public UploadInspector(DataSize maxSize) {
        this(Objects.requireNonNull(maxSize, "maxSize").toBytes());
    }

    public UploadInspector(FileServiceProperties properties) {
        this(Objects.requireNonNull(properties, "properties").maxBytes());
    }

    public UploadInspection open(InputStream source, String originalName, String declaredType,
                                 long declaredSize) {
        return open(source, originalName, declaredType, declaredSize, defaultMaxBytes);
    }

    public UploadInspection open(InputStream source, String originalName, String declaredType,
                                 long declaredSize, long maxBytes) {
        Objects.requireNonNull(source, "source");
        SafeDisplayName safeName = SafeDisplayName.from(originalName);
        if (declaredType == null || declaredType.isBlank()) {
            throw rejected("TYPE_MISMATCH", "declared media type is blank");
        }
        if (declaredSize < 0) {
            throw rejected("INVALID_SIZE", "declared size must not be negative");
        }
        if (maxBytes <= 0 || maxBytes > DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 byte and 20 MiB");
        }
        if (declaredSize > maxBytes) {
            throw rejected("FILE_TOO_LARGE", "declared size exceeds limit");
        }

        byte[] prefix;
        try {
            prefix = readPrefix(source, (int) Math.min((long) DETECTION_PREFIX_BYTES, maxBytes));
        } catch (IOException ex) {
            throw new UploadRejectedException("READ_FAILED", ex.getMessage());
        }
        DetectedFileType detected = detect(prefix, safeName);
        String normalizedDeclared = declaredType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        String deferredRejection = detected == null
            ? "TYPE_UNKNOWN: unsupported file signature"
            : (!detected.mediaType().equals(normalizedDeclared)
                ? "TYPE_MISMATCH: declared type does not match file signature"
                : (!detected.acceptsExtension(extensionOf(safeName.value()))
                    ? "TYPE_MISMATCH: filename extension does not match file signature" : null));
        return new InspectionImpl(source, prefix, safeName, normalizedDeclared, detected, maxBytes,
            deferredRejection);
    }

    private static byte[] readPrefix(InputStream source, int prefixLimit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(prefixLimit);
        byte[] buffer = new byte[4096];
        while (out.size() < prefixLimit) {
            int requested = Math.min(buffer.length, prefixLimit - out.size());
            int read = source.read(buffer, 0, requested);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int one = source.read();
                if (one < 0) {
                    break;
                }
                out.write(one);
            } else {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static DetectedFileType detect(byte[] prefix, SafeDisplayName name) {
        MediaType tikaType;
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name.value());
            tikaType = MimeTypes.getDefaultMimeTypes().detect(new ByteArrayInputStream(prefix), metadata);
        } catch (IOException ex) {
            throw rejected("TYPE_UNKNOWN", "unable to detect file type");
        }
        DetectedFileType signatureType = signatureType(prefix);
        return signatureType;
    }

    private static DetectedFileType signatureType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff) {
            return DetectedFileType.JPEG;
        }
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
            && bytes[2] == 'N' && bytes[3] == 'G' && (bytes[4] & 0xff) == 0x0d
            && (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a
            && (bytes[7] & 0xff) == 0x0a) {
            return DetectedFileType.PNG;
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
            && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
            && bytes[11] == 'P') {
            return DetectedFileType.WEBP;
        }
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
            && bytes[3] == 'F' && bytes[4] == '-') {
            return DetectedFileType.PDF;
        }
        return null;
    }

    private static UploadRejectedException rejected(String code, String detail) {
        return new UploadRejectedException(code, detail);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : "";
    }

    private static final class InspectionImpl implements UploadInspection {
        private final InputStream source;
        private final byte[] prefix;
        private final SafeDisplayName name;
        private final String declaredType;
        private final DetectedFileType detectedType;
        private final long maxBytes;
        private final String deferredRejection;
        private InputStream exposed;
        private LimitedDigestInputStream digestStream;
        private boolean finished;

        private InspectionImpl(InputStream source, byte[] prefix, SafeDisplayName name,
                               String declaredType, DetectedFileType detectedType, long maxBytes) {
            this(source, prefix, name, declaredType, detectedType, maxBytes, null);
        }

        private InspectionImpl(InputStream source, byte[] prefix, SafeDisplayName name,
                               String declaredType, DetectedFileType detectedType, long maxBytes,
                               String deferredRejection) {
            this.source = source;
            this.prefix = prefix;
            this.name = name;
            this.declaredType = declaredType;
            this.detectedType = detectedType;
            this.maxBytes = maxBytes;
            this.deferredRejection = deferredRejection;
        }

        @Override
        public InputStream stream() {
            if (exposed != null) {
                throw new IllegalStateException("inspection stream may only be opened once");
            }
            try {
                digestStream = new LimitedDigestInputStream(
                    new SequenceInputStream(new ByteArrayInputStream(prefix), source), maxBytes);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is unavailable", ex);
            }
            exposed = digestStream;
            return exposed;
        }

        @Override
        public InspectedUpload finish(TemporaryObject temporaryObject) {
            if (digestStream == null || !digestStream.eof()) {
                throw new IllegalStateException("inspection stream must be consumed before finish");
            }
            if (deferredRejection != null) {
                String[] parts = deferredRejection.split(": ", 2);
                throw new UploadRejectedException(parts[0], parts.length == 2 ? parts[1] : null);
            }
            if (finished) {
                throw new IllegalStateException("inspection already finished");
            }
            finished = true;
            return new InspectedUpload(name, declaredType, detectedType, digestStream.count(),
                HexFormat.of().formatHex(digestStream.digest()), temporaryObject);
        }
    }

    private static final class SequenceInputStream extends InputStream {
        private final InputStream first;
        private final InputStream second;
        private boolean firstDone;

        private SequenceInputStream(InputStream first, InputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int read() throws IOException {
            if (!firstDone) {
                int value = first.read();
                if (value >= 0) return value;
                firstDone = true;
            }
            return second.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!firstDone) {
                int n = first.read(b, off, len);
                if (n > 0) return n;
                if (n == 0) return 0;
                firstDone = true;
            }
            return second.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            first.close();
            second.close();
        }
    }

    private static final class LimitedDigestInputStream extends InputStream {
        private final InputStream delegate;
        private final MessageDigest digest;
        private final long maxBytes;
        private long count;
        private boolean eof;

        private LimitedDigestInputStream(InputStream delegate, long maxBytes) throws NoSuchAlgorithmException {
            this.delegate = delegate;
            this.digest = MessageDigest.getInstance("SHA-256");
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (count >= maxBytes) {
                int extra = delegate.read();
                if (extra >= 0) throw tooLarge();
                eof = true;
                return -1;
            }
            int value = delegate.read();
            if (value < 0) {
                eof = true;
                return -1;
            }
            digest.update((byte) value);
            count++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (count >= maxBytes) {
                int extra = delegate.read();
                if (extra >= 0) throw tooLarge();
                eof = true;
                return -1;
            }
            int allowed = (int) Math.min((long) len, maxBytes - count + 1);
            int n = delegate.read(b, off, allowed);
            if (n < 0) {
                eof = true;
                return -1;
            }
            if (n > maxBytes - count) throw tooLarge();
            digest.update(b, off, n);
            count += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private UploadRejectedException tooLarge() {
            return new UploadRejectedException("FILE_TOO_LARGE", "upload exceeds byte limit");
        }

        private boolean eof() { return eof; }
        private long count() { return count; }
        private byte[] digest() { return digest.digest(); }
    }
}
