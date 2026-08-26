package com.example.files.application.upload;

/** A client-upload rejection with a stable machine-readable code in its message. */
public class UploadRejectedException extends RuntimeException {
    private final String code;

    public UploadRejectedException(String code, String detail) {
        super(code + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.code = code;
    }

    public UploadRejectedException(String code) {
        this(code, null);
    }

    public String code() {
        return code;
    }
}
