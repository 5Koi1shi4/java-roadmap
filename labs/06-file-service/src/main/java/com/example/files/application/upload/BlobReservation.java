package com.example.files.application.upload;

import java.util.Objects;
import java.util.UUID;

/**
 * Blob 领取结果的显式状态模型。
 *
 * <p>等待状态不携带任何会话、令牌或对象存储能力，只有获得能力的结果
 * 才能传给终结 API。这样调用方无法把等待结果误当成可提交凭据。</p>
 */
public sealed interface BlobReservation permits BlobReservation.Waiting, BlobReservation.Granted {
    Mode mode();

    boolean reusesReadyBlob();

    /** 带有会话绑定能力的领取结果。 */
    sealed interface Granted extends BlobReservation permits Owned, ReadyReuse {
        UUID sessionId();

        UUID ownerToken();

        long blobId();

        String objectKey();
    }

    /** 新建或继续持有 STAGING Blob 的能力。 */
    record Owned(UUID sessionId, UUID ownerToken, long blobId, String objectKey, Mode mode)
        implements Granted {
        public Owned {
            validateCapability(sessionId, ownerToken, blobId, objectKey);
            if (mode != Mode.NEW_STAGING && mode != Mode.OWNED_STAGING) {
                throw new IllegalArgumentException("Owned reservation has invalid mode");
            }
        }

        @Override
        public boolean reusesReadyBlob() {
            return false;
        }
    }

    /** 已 READY Blob 的复用能力。 */
    record ReadyReuse(UUID sessionId, UUID ownerToken, long blobId, String objectKey)
        implements Granted {
        public ReadyReuse {
            validateCapability(sessionId, ownerToken, blobId, objectKey);
        }

        @Override
        public Mode mode() {
            return Mode.REUSE_READY;
        }

        @Override
        public boolean reusesReadyBlob() {
            return true;
        }
    }

    /** 等待其他会话完成 Blob 提交；故意不暴露内部标识。 */
    record Waiting() implements BlobReservation {
        @Override
        public Mode mode() {
            return Mode.WAITING;
        }

        @Override
        public boolean reusesReadyBlob() {
            return false;
        }
    }

    static Owned newStaging(UUID sessionId, UUID ownerToken, long blobId, String objectKey) {
        return new Owned(sessionId, ownerToken, blobId, objectKey, Mode.NEW_STAGING);
    }

    static Owned ownedStaging(UUID sessionId, UUID ownerToken, long blobId, String objectKey) {
        return new Owned(sessionId, ownerToken, blobId, objectKey, Mode.OWNED_STAGING);
    }

    static ReadyReuse readyReuse(UUID sessionId, UUID ownerToken, long blobId, String objectKey) {
        return new ReadyReuse(sessionId, ownerToken, blobId, objectKey);
    }

    static Waiting waiting() {
        return new Waiting();
    }

    enum Mode { NEW_STAGING, OWNED_STAGING, WAITING, REUSE_READY }

    private static void validateCapability(UUID sessionId, UUID ownerToken, long blobId, String objectKey) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (blobId <= 0 || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Blob reservation contains invalid capability");
        }
    }
}
