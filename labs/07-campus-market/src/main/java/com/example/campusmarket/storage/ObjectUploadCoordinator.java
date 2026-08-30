package com.example.campusmarket.storage;

import com.example.campusmarket.catalog.application.ListingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
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
        byte[] bytes = readLimit(input, 10 * 1024 * 1024L);
        String detected = new org.apache.tika.Tika().detect(bytes, filename);
        validateType(filename, declaredContentType, detected);
        String key = "listing-media/" + randomToken();
        UUID sessionId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3600);
        createSession(sessionId, actorId, key, expiry);
        boolean stored = false;
        try {
            storage.put(key, new ByteArrayInputStream(bytes), bytes.length, detected);
            stored = true;
            UUID mediaId = UUID.randomUUID();
            int sortOrder = nextSortOrder(listingId);
            listings.saveMedia(listingId, mediaId, key, detected, bytes.length, sortOrder);
            completeSession(sessionId);
            return new ListingRepository.MediaRecord(mediaId, listingId, key, detected, bytes.length, sortOrder);
        } catch (RuntimeException e) {
            abortSession(sessionId);
            createCleanupTask(sessionId, key);
            if (stored) {
                // 清理任务负责事务外补偿，当前请求不再同步重试对象删除。
            }
            throw e;
        }
    }

    private int nextSortOrder(UUID listingId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), -1) FROM listing_media WHERE listing_id = ?",
            Integer.class, listingId.toString());
        return (max == null ? -1 : max) + 1;
    }

    protected void createSession(UUID id, UUID actorId, String key, Instant expiry) {
        transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO object_upload_session (id, submitted_by, purpose, object_key, status, expires_at, created_at, updated_at) VALUES (?, ?, 'LISTING_MEDIA', ?, 'OPEN', ?, ?, ?)",
            id.toString(), actorId.toString(), key, Timestamp.from(expiry), Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
    }

    protected void completeSession(UUID id) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE object_upload_session SET status='COMPLETED', updated_at=? WHERE id=? AND status='OPEN'",
            Timestamp.from(Instant.now()), id.toString()));
    }

    protected void abortSession(UUID id) {
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE object_upload_session SET status='ABORTED', updated_at=? WHERE id=? AND status='OPEN'",
            Timestamp.from(Instant.now()), id.toString()));
    }

    protected void createCleanupTask(UUID sessionId, String key) {
        transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO storage_cleanup_task (id, cleanup_business_key, object_key, status, run_after, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', ?, ?, ?) ON DUPLICATE KEY UPDATE updated_at=VALUES(updated_at)",
            UUID.randomUUID().toString(), "listing-upload:" + sessionId, key, Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
    }

    static byte[] readLimit(InputStream input, long limit) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IllegalArgumentException("文件超过10MiB限制");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取媒体失败", e);
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
}
