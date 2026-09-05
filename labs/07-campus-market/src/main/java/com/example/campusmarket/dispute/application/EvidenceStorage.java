package com.example.campusmarket.dispute.application;

import com.example.campusmarket.dispute.infrastructure.JdbcDisputeRepository;
import com.example.campusmarket.storage.PrivateObjectStorage;
import com.example.campusmarket.storage.MinioPrivateObjectStorage;
import org.apache.tika.Tika;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 普通争议私有证据的流式边界、类型验证和 ACL 门禁。 */
@Service
@Profile("!test")
public final class EvidenceStorage {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_BYTES = 100 * MIB;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Tika TIKA = new Tika();
    private final JdbcDisputeRepository repository;
    private final PrivateObjectStorage storage;
    private final EvidenceCaseAccess access;
    private final TransactionTemplate transactions;

    public EvidenceStorage(JdbcDisputeRepository repository, PrivateObjectStorage storage, EvidenceCaseAccess access,
                           org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "争议仓储不能为空");
        this.storage = Objects.requireNonNull(storage, "对象存储不能为空");
        this.access = Objects.requireNonNull(access, "证据权限不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    public EvidenceRecord attach(UUID caseId, UUID actorId, String filename, String declaredType, InputStream input) {
        if (!access.canAttach("DISPUTE", caseId, actorId)) throw new NotFoundException();
        UUID session = UUID.randomUUID();
        String key = "dispute-evidence/" + randomToken();
        String claimToken = randomToken();
        transactions.executeWithoutResult(s -> repository.createSession(session, actorId, key, claimToken));
        Path temp = null;
        try {
            temp = readBounded(input).path();
            String detected = TIKA.detect(temp.toFile());
            long size = Files.size(temp);
            validate(filename, declaredType, detected, size);
            try (InputStream content = Files.newInputStream(temp)) { storage.put(key, content, size, detected); }
            UUID evidenceId = UUID.randomUUID();
            EvidenceRecord result = transactions.execute(s -> {
                if (!access.canAttach("DISPUTE", caseId, actorId)) throw new NotFoundException();
                InstantHolder now = new InstantHolder(repository.databaseNow());
                repository.insertEvidence(evidenceId, caseId, actorId, key, detected, size, now.value);
                if (repository.completeSession(session, actorId, claimToken) != 1)
                    throw new IllegalStateException("上传会话已过期或已被接管");
                return new EvidenceRecord(evidenceId, caseId, detected, size);
            });
            return result;
        } catch (RuntimeException | IOException ex) {
            compensateFailure(session, actorId, claimToken, key, ex);
            if (ex instanceof MinioPrivateObjectStorage.StorageUnavailableException) throw new StorageUnavailableException();
            throw ex instanceof RuntimeException r ? r : new IllegalArgumentException("读取证据失败", ex);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public OpenedEvidence open(UUID caseId, UUID evidenceId, UUID actorId) {
        if (!access.canRead("DISPUTE", caseId, actorId)) throw new NotFoundException();
        JdbcDisputeRepository.EvidenceRow evidence = repository.evidence(evidenceId);
        if (evidence == null || !caseId.equals(evidence.caseId()) || !access.canRead("DISPUTE", caseId, actorId)) throw new NotFoundException();
        try { return new OpenedEvidence(storage.open(evidence.objectKey()), evidence.mediaType()); }
        catch (MinioPrivateObjectStorage.StorageUnavailableException ex) { throw new StorageUnavailableException(); }
        catch (MinioPrivateObjectStorage.ObjectNotFoundException ex) { throw new NotFoundException(); }
    }

    private static BoundedRead readBounded(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("证据文件不能为空");
        Path file = Files.createTempFile("campus-evidence-", ".upload");
        try (InputStream in = input; OutputStream out = Files.newOutputStream(file)) {
            byte[] prefix = readPrefix(in);
            String preliminary = signatureType(prefix);
            long limit = limitFor(preliminary);
            out.write(prefix);
            long total = prefix.length;
            byte[] buffer = new byte[8192]; int read;
            while (total <= limit && (read = in.read(buffer, 0, (int) Math.min(buffer.length, limit + 1 - total))) != -1) {
                if (read == 0) continue;
                total += read;
                if (total > limit) throw new IllegalArgumentException("证据超过该类型大小限制");
                out.write(buffer, 0, read);
            }
            if (total > limit) throw new IllegalArgumentException("证据超过该类型大小限制");
            return new BoundedRead(file, preliminary);
        } catch (IOException | RuntimeException ex) { try { Files.deleteIfExists(file); } catch (IOException ignored) { } throw ex; }
    }

    private void compensateFailure(UUID session, UUID owner, String claimToken, String key, Throwable original) {
        RuntimeException compensationFailure = null;
        try { transactions.executeWithoutResult(s -> repository.abortSession(session, owner, claimToken)); }
        catch (RuntimeException ex) { compensationFailure = ex; }
        try { transactions.executeWithoutResult(s -> repository.cleanup(session, key)); }
        catch (RuntimeException ex) { if (compensationFailure == null) compensationFailure = ex; else compensationFailure.addSuppressed(ex); }
        if (compensationFailure != null) original.addSuppressed(compensationFailure);
    }

    private static byte[] readPrefix(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[512];
        while (bytes.size() < 512) {
            int read = in.read(buffer, 0, Math.min(buffer.length, 512 - bytes.size()));
            if (read < 0) break;
            if (read == 0) continue;
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static String signatureType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') return "application/pdf";
        if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') return "video/mp4";
        return "";
    }

    private static long limitFor(String type) {
        return switch (type) {
            case "image/jpeg", "image/png", "image/webp" -> 10 * MIB;
            case "application/pdf" -> 20 * MIB;
            case "video/mp4", "" -> MAX_BYTES;
            default -> MAX_BYTES;
        };
    }

    private static void validate(String filename, String declared, String detected, long size) throws IOException {
        long limit = switch (detected) {
            case "image/jpeg", "image/png", "image/webp" -> 10 * MIB;
            case "application/pdf" -> 20 * MIB;
            case "video/mp4" -> 100 * MIB;
            default -> -1;
        };
        if (limit < 0) throw new IllegalArgumentException("不支持的证据类型");
        if (size > limit) throw new IllegalArgumentException("证据超过该类型大小限制");
        if (declared != null && !declared.isBlank() && !detected.equalsIgnoreCase(declared.trim()))
            throw new IllegalArgumentException("证据声明类型与实际类型不一致");
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT);
            boolean match = (detected.equals("image/jpeg") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg")))
                || (detected.equals("image/png") && lower.endsWith(".png"))
                || (detected.equals("image/webp") && lower.endsWith(".webp"))
                || (detected.equals("application/pdf") && lower.endsWith(".pdf"))
                || (detected.equals("video/mp4") && lower.endsWith(".mp4"));
            if (!match) throw new IllegalArgumentException("证据扩展名与实际类型不一致");
        }
    }

    private static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public record EvidenceRecord(UUID id, UUID caseId, String mediaType, long sizeBytes) { }
    public record OpenedEvidence(InputStream content, String mediaType) { }
    private record BoundedRead(Path path, String preliminaryType) { }
    public static class NotFoundException extends RuntimeException { }
    public static class StorageUnavailableException extends RuntimeException { }
    private record InstantHolder(java.time.Instant value) { }
}
