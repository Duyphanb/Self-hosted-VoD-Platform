package com.vodplatform.shared.storage;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.OptionalLong;

/** A streaming read with optional length metadata; closing it closes the storage response. */
public final class StoredObject extends FilterInputStream {
    private final long size;

    /** A negative size means storage did not report a length. No content is buffered here. */
    public StoredObject(InputStream stream, long size) {
        super(Objects.requireNonNull(stream, "stream"));
        this.size = size;
    }

    public OptionalLong size() {
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }
}
