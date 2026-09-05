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
        transactions.executeWithoutResult(s -> repository.createSession(session, actorId, key));
        Path temp = null;
        try {
            temp = readBounded(input);
            String detected = TIKA.detect(temp.toFile());
            long size = Files.size(temp);
            validate(filename, declaredType, detected, size);
            try (InputStream content = Files.newInputStream(temp)) { storage.put(key, content, size, detected); }
            UUID evidenceId = UUID.randomUUID();
            EvidenceRecord result = transactions.execute(s -> {
                if (!access.canAttach("DISPUTE", caseId, actorId)) throw new NotFoundException();
                InstantHolder now = new InstantHolder(repository.databaseNow());
                repository.insertEvidence(evidenceId, caseId, actorId, key, detected, size, now.value);
                repository.completeSession(session);
                return new EvidenceRecord(evidenceId, caseId, detected, size);
            });
            return result;
        } catch (RuntimeException | IOException ex) {
            try { transactions.executeWithoutResult(s -> repository.abortSession(session)); } catch (RuntimeException ignored) { }
            try { transactions.executeWithoutResult(s -> repository.cleanup(session, key)); } catch (RuntimeException ignored) { }
            if (ex instanceof MinioPrivateObjectStorage.StorageUnavailableException) throw new StorageUnavailableException();
            throw ex instanceof RuntimeException r ? r : new IllegalArgumentException("读取证据失败", ex);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public InputStream open(UUID caseId, UUID evidenceId, UUID actorId) {
        if (!access.canRead("DISPUTE", caseId, actorId)) throw new NotFoundException();
        JdbcDisputeRepository.EvidenceRow evidence = repository.evidence(evidenceId);
        if (evidence == null || !caseId.equals(evidence.caseId()) || !access.canRead("DISPUTE", caseId, actorId)) throw new NotFoundException();
        try { return storage.open(evidence.objectKey()); }
        catch (MinioPrivateObjectStorage.StorageUnavailableException ex) { throw new StorageUnavailableException(); }
        catch (MinioPrivateObjectStorage.ObjectNotFoundException ex) { throw new NotFoundException(); }
    }

    private static Path readBounded(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("证据文件不能为空");
        Path file = Files.createTempFile("campus-evidence-", ".upload");
        try (InputStream in = input; OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192]; long total = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new IllegalArgumentException("证据超过100MiB限制");
                out.write(buffer, 0, read);
            }
            return file;
        } catch (IOException | RuntimeException ex) { try { Files.deleteIfExists(file); } catch (IOException ignored) { } throw ex; }
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
    public static class NotFoundException extends RuntimeException { }
    public static class StorageUnavailableException extends RuntimeException { }
    private record InstantHolder(java.time.Instant value) { }
}
