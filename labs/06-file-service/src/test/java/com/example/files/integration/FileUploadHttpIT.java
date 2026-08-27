package com.example.files.integration;

import com.example.files.api.ApiExceptionHandler;
import com.example.files.api.CorrelationIdFilter;
import com.example.files.api.FileController;
import com.example.files.api.security.TrustedHeaderIdentityResolver;
import com.example.files.application.upload.UploadResult;
import com.example.files.application.upload.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileUploadHttpIT {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UploadService service = mock(UploadService.class);
        when(service.upload(any())).thenReturn(new UploadResult(UUID.randomUUID(), "课程资料.pdf",
            "application/pdf", 13L, Instant.parse("2026-08-27T00:00:00Z")));
        mvc = MockMvcBuilders.standaloneSetup(new FileController(service, new TrustedHeaderIdentityResolver()))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void missingIdentityIs401WithUtf8Json() throws Exception {
        mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", "课程资料.pdf", "application/pdf", pdfBytes())))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void duplicateIdentityHeaderIs401() throws Exception {
        mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", "课程资料.pdf", "application/pdf", pdfBytes()))
                .header("X-Trusted-User-Id", "42")
                .header("X-Trusted-User-Id", "43"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void missingFilePartIs400() throws Exception {
        mvc.perform(multipart("/api/files")
                .header("X-Trusted-User-Id", "42"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void successfulUploadReturnsOnlyLogicalFields() throws Exception {
        mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", "课程资料.pdf", "application/pdf", pdfBytes()))
                .header("X-Trusted-User-Id", "42"))
            .andExpect(status().isCreated())
            .andExpect(content().contentType("application/json;charset=UTF-8"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hash"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("objectKey"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("dedup"))));
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7\ncontent".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
