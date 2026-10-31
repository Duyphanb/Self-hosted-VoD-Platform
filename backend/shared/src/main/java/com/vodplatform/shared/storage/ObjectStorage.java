package com.vodplatform.shared.storage;

import java.io.IOException;
import java.io.InputStream;

/** Streaming storage boundary. Callers own and close both input and returned streams. */
public interface ObjectStorage {
    void put(String bucket, String key, InputStream source, long size, String contentType) throws IOException;

    InputStream get(String bucket, String key) throws IOException;

    void delete(String bucket, String key) throws IOException;
}
