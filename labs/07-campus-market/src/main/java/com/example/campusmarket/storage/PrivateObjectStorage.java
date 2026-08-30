package com.example.campusmarket.storage;

import java.io.InputStream;

public interface PrivateObjectStorage {
    void put(String objectKey, InputStream content, long sizeBytes, String contentType);
    InputStream open(String objectKey);
    void delete(String objectKey);
}
