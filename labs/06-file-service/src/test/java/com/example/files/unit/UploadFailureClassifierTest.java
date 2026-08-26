package com.example.files.unit;

import com.example.files.application.upload.UploadFailureClassifier;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传故障分类必须保持稳定且不泄露底层异常信息。 */
class UploadFailureClassifierTest {
    @Test
    void classifiesNestedUncheckedIoAsStorageIo() {
        RuntimeException error = new RuntimeException("wrapper", new UncheckedIOException(new java.io.IOException("secret path")));
        assertThat(new UploadFailureClassifier().classify(error)).isEqualTo("STORAGE_IO");
    }
}
