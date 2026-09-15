package com.harmoniasuite.source.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import org.junit.jupiter.api.Test;

class SourceIngestionArchitectureTest {

    @Test
    void canonicalMetadataIsTheSingleComparableValue() {
        SourceSnapshotMetadata first = metadata();
        SourceSnapshotMetadata same = metadata();
        SourceSnapshotMetadata different = new SourceSnapshotMetadata(1, "7.3", "en", "full",
                first.snapshotId(), first.contentId(), "extractor", "lumina", 1, 0, 0);

        assertEquals(first, same);
        assertNotEquals(first, different);
    }

    @Test
    void sha256DigestUsesDefensiveCopiesAndLowercaseHex() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xab;
        Sha256Digest digest = Sha256Digest.of(bytes);
        bytes[0] = 0;

        assertEquals("ab" + "00".repeat(31), digest.hex());
        assertEquals(32, digest.bytes().length);
    }

    private static SourceSnapshotMetadata metadata() {
        return new SourceSnapshotMetadata(1, "7.2", "en", "full",
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "extractor", "lumina", 1, 0, 0);
    }
}
