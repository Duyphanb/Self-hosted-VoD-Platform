package com.vodplatform.shared.storage;

import java.io.IOException;

/** A confirmed missing object, with no storage coordinates or SDK diagnostics. */
public final class ObjectMissingException extends IOException {
    public ObjectMissingException() {
        super("Object storage object not found");
    }
}
