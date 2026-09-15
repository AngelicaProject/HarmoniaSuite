package com.harmoniasuite.source.upload;

import java.util.concurrent.ConcurrentHashMap;

public final class UploadSessionLocks {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private UploadSessionLocks() {
    }

    public static Object forUpload(String uploadId) {
        return LOCKS.computeIfAbsent(uploadId, ignored -> new Object());
    }
}
