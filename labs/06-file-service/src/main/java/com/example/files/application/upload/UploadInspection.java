package com.example.files.application.upload;

import java.io.InputStream;

/** A single-pass upload body plus metadata finalized once storage consumed it. */
public interface UploadInspection {
    InputStream stream();

    InspectedUpload finish(TemporaryObject temporaryObject);
}
