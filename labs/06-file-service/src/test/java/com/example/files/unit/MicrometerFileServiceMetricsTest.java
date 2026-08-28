package com.example.files.unit;

import com.example.files.observability.MicrometerFileServiceMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerFileServiceMetricsTest {
    @Test
    void metricsNeverUseResourceOrIdentityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerFileServiceMetrics metrics = new MicrometerFileServiceMetrics(registry);
        metrics.recordUpload("success", Duration.ofMillis(10));
        metrics.recordSession("RECEIVING");
        metrics.recordBlob("READY");
        metrics.setStagingOldest(Duration.ofSeconds(3));
        metrics.recordCleanupPending("TEMP_OBJECT");
        metrics.recordCleanupRetry("BLOB_OBJECT", "retry");
        metrics.recordDownload("authorization", "success");
        metrics.recordAcl("grant", "success");
        metrics.recordStorageOperation("open", "success", Duration.ofMillis(2));

        assertThat(registry.getMeters()).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).noneMatch(tag -> Set.of("userId", "fileId", "hash",
                "objectKey", "tempKey", "blobId", "correlationId", "exception", "path", "url")
                .contains(tag.getKey())));
        assertThat(registry.find("file.upload.total").tag("result", "success").counter().count()).isEqualTo(1);
    }

    @Test
    void invalidLabelsFailAtBoundaryInsteadOfBeingRegistered() {
        MicrometerFileServiceMetrics metrics = new MicrometerFileServiceMetrics(new SimpleMeterRegistry());
        assertThatThrownBy(() -> metrics.recordUpload("user-42", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordDownload("phase-with-file-id", "success")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordAcl("administrator", "success")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordStorageOperation("file-id", "success", Duration.ofMillis(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
