package com.example.campusmarket.storage;

import com.example.campusmarket.observability.CampusMetrics;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioPrivateObjectStorageReadTest {
    @Test
    void recordsSuccessOnlyWhenReadReachesEofAndDoesNotDoubleCountOnClose() throws Exception {
        MinioClient client = mock(MinioClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response(new java.io.ByteArrayInputStream(new byte[] {1, 2})));
        MinioPrivateObjectStorage storage = new MinioPrivateObjectStorage(client, "bucket", metrics);

        InputStream stream = storage.open("object");
        assertThat(registry.find("campus.market.storage.operation.total").tag("result", "SUCCESS").counter()).isNull();
        assertThat(stream.readAllBytes()).containsExactly(1, 2);
        stream.close();

        assertThat(registry.get("campus.market.storage.operation.total").tag("result", "SUCCESS").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("campus.market.operation.duration").tag("operation", "READ").timer().count())
            .isEqualTo(1L);
    }

    @Test
    void recordsFailureWhenADeferredReadThrowsAndDoesNotRecordSuccessOnClose() throws Exception {
        MinioClient client = mock(MinioClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("body unavailable"); }
        }));
        MinioPrivateObjectStorage storage = new MinioPrivateObjectStorage(client, "bucket", metrics);
        InputStream stream = storage.open("object");

        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
        stream.close();

        assertThat(registry.get("campus.market.storage.operation.total").tag("result", "FAILURE").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("campus.market.storage.operation.total").tag("result", "SUCCESS").counter()).isNull();
        assertThat(registry.get("campus.market.operation.duration").tag("operation", "READ").timer().count())
            .isEqualTo(1L);
    }

    @Test
    void recordsFailureWhenClosingTheDeferredReadThrows() throws Exception {
        MinioClient client = mock(MinioClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response(new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException { throw new IOException("close unavailable"); }
        }));
        MinioPrivateObjectStorage storage = new MinioPrivateObjectStorage(client, "bucket", metrics);
        InputStream stream = storage.open("object");

        assertThatThrownBy(stream::close).isInstanceOf(IOException.class);

        assertThat(registry.get("campus.market.storage.operation.total").tag("result", "FAILURE").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("campus.market.storage.operation.total").tag("result", "SUCCESS").counter()).isNull();
    }

    private GetObjectResponse response(InputStream stream) {
        return new GetObjectResponse(new okhttp3.Headers.Builder().build(), "bucket", "region", "object", stream);
    }
}
