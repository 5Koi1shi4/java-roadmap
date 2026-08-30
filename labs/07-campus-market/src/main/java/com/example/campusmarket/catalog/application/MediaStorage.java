package com.example.campusmarket.catalog.application;

import java.io.InputStream;

public interface MediaStorage {
    void put(String objectKey, InputStream content, long sizeBytes, String contentType);
    InputStream open(String objectKey);
    void delete(String objectKey);
}
