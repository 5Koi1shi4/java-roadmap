package com.example.files.unit;

import com.example.files.application.upload.InspectedUpload;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadInspection;
import com.example.files.application.upload.UploadInspector;
import com.example.files.application.upload.UploadRejectedException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadInspectorTest {

    private final UploadInspector inspector = new UploadInspector();

    @Test
    void rejectsBytesBeyondLimitEvenWhenDeclaredSizeIsSmall() {
        InputStream body = new ByteArrayInputStream(new byte[21 * 1024 * 1024]);

        assertThatThrownBy(() -> fullyInspect(body, "a.png", "image/png", 1L))
            .isInstanceOf(UploadRejectedException.class)
            .hasMessageContaining("FILE_TOO_LARGE");
    }

    @Test
    void rejectsPdfBytesDeclaredAsPng() {
        byte[] pdf = pdfBytes();

        assertThatThrownBy(() -> fullyInspect(new ByteArrayInputStream(pdf),
            "a.png", "image/png", pdf.length))
            .isInstanceOf(UploadRejectedException.class)
            .hasMessageContaining("TYPE_MISMATCH");
    }

    @Test
    void inspectsAllowedSignaturesAndPreservesTheSingleInputStream() throws Exception {
        byte[][] bodies = {
            new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01},
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x01},
            new byte[] {'R', 'I', 'F', 'F', 0x01, 0, 0, 0, 'W', 'E', 'B', 'P', 0x01},
            pdfBytes()
        };
        String[] names = {"a.jpg", "a.png", "a.webp", "a.pdf"};
        String[] types = {"image/jpeg", "image/png", "image/webp", "application/pdf"};

        for (int i = 0; i < bodies.length; i++) {
            UploadInspection inspection = inspector.open(new ByteArrayInputStream(bodies[i]),
                names[i], types[i], bodies[i].length);
            assertThat(inspection.stream().readAllBytes()).containsExactly(bodies[i]);
            InspectedUpload result = inspection.finish(new TemporaryObject("tmp/test", bodies[i].length));
            assertThat(result.actualSize()).isEqualTo(bodies[i].length);
            assertThat(result.declaredType()).isEqualTo(types[i]);
        }
    }

    private InspectedUpload fullyInspect(InputStream body, String name, String declaredType,
                                         long declaredSize) throws Exception {
        UploadInspection inspection = inspector.open(body, name, declaredType, declaredSize);
        byte[] bytes = inspection.stream().readAllBytes();
        return inspection.finish(new TemporaryObject("tmp/test", bytes.length));
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7\n% test\n".getBytes(StandardCharsets.US_ASCII);
    }
}
