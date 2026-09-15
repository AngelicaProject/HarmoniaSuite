package com.harmoniasuite.source.upload;

import java.util.Objects;

public final class UploadSessionLocks {

    private static final Object[] LOCKS = new Object[256];

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private UploadSessionLocks() {
    }

    public static Object forUpload(String uploadId) {
        Objects.requireNonNull(uploadId, "uploadId");
        int hash = uploadId.hashCode();
        hash ^= hash >>> 16;
        return LOCKS[Math.floorMod(hash, LOCKS.length)];
    }
}
