package com.example.campusmarket.storage;

import com.example.campusmarket.catalog.application.ListingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class ObjectUploadCoordinator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PrivateObjectStorage storage;
    private final ListingRepository listings;
    private final TransactionTemplate transactions;

    public ObjectUploadCoordinator(JdbcTemplate jdbc, PrivateObjectStorage storage, ListingRepository listings,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.listings = listings;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ListingRepository.MediaRecord uploadListingMedia(UUID listingId, UUID actorId, String filename,
                                                              String declaredContentType, InputStream input) {
        Path temporaryFile = null;
        String key = "listing-media/" + randomToken();
        UUID sessionId = UUID.randomUUID();
        createSession(sessionId, actorId, key);
        try {
            temporaryFile = readToTemporaryFile(input, 10 * 1024 * 1024L);
            String detected = new org.apache.tika.Tika().detect(temporaryFile.toFile());
            validateType(filename, declaredContentType, detected);
            long size = Files.size(temporaryFile);
            try (InputStream content = Files.newInputStream(temporaryFile)) {
                storage.put(key, content, size, detected);
            }
            return bindMediaAndComplete(listingId, sessionId, key, detected, size);
        } catch (RuntimeException e) {
            compensateFailure(sessionId, key);
            throw e;
        } catch (IOException e) {
            compensateFailure(sessionId, key);
            throw new IllegalArgumentException("读取媒体失败", e);
        } finally {
            if (temporaryFile != null) {
                try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
            }
        }
    }

    public ListingRepository.MediaRecord bindMediaAndComplete(UUID listingId, UUID sessionId, String key,
                                                                 String detected, long size) {
        return transactions.execute(status -> {
            jdbc.query("SELECT id FROM listing WHERE id = ? FOR UPDATE", rs -> {
                if (!rs.next()) throw new ListingNotFoundException();
                return null;
            }, listingId.toString());
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM listing_media WHERE listing_id = ?", Integer.class, listingId.toString());
            if (count == null || count >= 9) throw new IllegalStateException("每个商品最多添加9张媒体");
            Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), -1) FROM listing_media WHERE listing_id = ?",
                Integer.class, listingId.toString());
            UUID mediaId = UUID.randomUUID();
            int sortOrder = (max == null ? -1 : max) + 1;
            jdbc.update("INSERT INTO listing_media (id, listing_id, object_key, media_type, size_bytes, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
                mediaId.toString(), listingId.toString(), key, detected, size, sortOrder);
            int completed = jdbc.update("UPDATE object_upload_session SET status='COMPLETED', updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",
                sessionId.toString());
            if (completed != 1) throw new IllegalStateException("上传会话状态无效");
            return new ListingRepository.MediaRecord(mediaId, listingId, key, detected, size, sortOrder);
        });
    }

    protected void createSession(UUID id, UUID actorId, String key) {
        transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO object_upload_session (id, submitted_by, purpose, object_key, status, expires_at, created_at, updated_at) VALUES (?, ?, 'LISTING_MEDIA', ?, 'OPEN', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            id.toString(), actorId.toString(), key));
    }

    protected void completeSession(UUID id) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE object_upload_session SET status='COMPLETED', updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",
            id.toString()));
    }

    protected void abortSession(UUID id) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE object_upload_session SET status='ABORTED', updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",
            id.toString()));
    }

    protected void createCleanupTask(UUID sessionId, String key) {
        transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO storage_cleanup_task (id, cleanup_business_key, object_key, status, run_after, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP(6)",
            UUID.randomUUID().toString(), "listing-upload:" + sessionId, key));
    }

    private void compensateFailure(UUID sessionId, String key) {
        try { abortSession(sessionId); } catch (RuntimeException ignored) { }
        try { createCleanupTask(sessionId, key); } catch (RuntimeException ignored) { }
    }

    private static Path readToTemporaryFile(InputStream input, long limit) throws IOException {
        Path file = Files.createTempFile("campus-listing-", ".upload");
        try (InputStream in = input; java.io.OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IllegalArgumentException("文件超过10MiB限制");
                out.write(buffer, 0, read);
            }
            return file;
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static void validateType(String filename, String declared, String detected) {
        if (!SetTypes.ALLOWED.contains(detected)) throw new IllegalArgumentException("不支持的媒体类型");
        if (declared != null && !declared.isBlank() && !detected.equalsIgnoreCase(declared)) {
            throw new IllegalArgumentException("媒体声明类型与实际类型不一致");
        }
        if (filename != null) {
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            boolean extensionMatches = (detected.equals("image/jpeg") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg")))
                || (detected.equals("image/png") && lower.endsWith(".png"))
                || (detected.equals("image/webp") && lower.endsWith(".webp"));
            if (!extensionMatches) throw new IllegalArgumentException("文件扩展名与实际类型不一致");
        }
    }

    static String randomToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static final class SetTypes {
        private static final java.util.Set<String> ALLOWED = java.util.Set.of("image/jpeg", "image/png", "image/webp");
    }

    public static class ListingNotFoundException extends RuntimeException { }
}
