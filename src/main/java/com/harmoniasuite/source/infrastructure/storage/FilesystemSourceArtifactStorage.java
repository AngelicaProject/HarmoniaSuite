package com.harmoniasuite.source.infrastructure.storage;

import com.harmoniasuite.source.application.model.StoredSourceArtifact;
import com.harmoniasuite.source.application.port.SourceArtifactStorage;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.Sha256Id;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Filesystem implementation of immutable compressed HXS artifact storage. */
public final class FilesystemSourceArtifactStorage implements SourceArtifactStorage {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ConcurrentHashMap<String, ArtifactLock> ARTIFACT_LOCKS = new ConcurrentHashMap<>();

    private final SourceArtifactPath paths;

    public FilesystemSourceArtifactStorage(SourceArtifactPath paths) {
        this.paths = paths;
    }

    @Override
    public StoredSourceArtifact storeHxs(Path hxsPath, Sha256Id trustedSnapshotId,
                                          long uncompressedSize, Sha256Digest hxsFileHash,
                                          int compressionLevel) throws IOException {
        ArtifactLock lock = acquire(trustedSnapshotId.value());
        try {
            SourceArtifactPath.requireRegularFile(hxsPath, "verified HXS");
            Path finalPath = paths.artifactPath(trustedSnapshotId);
            paths.createArtifactParent(finalPath);
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
                return existing(finalPath, trustedSnapshotId, uncompressedSize, hxsFileHash);
            }
            Path temporary = temporary(finalPath);
            try {
                MessageDigest hxsDigest = sha256();
                long copied = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                try (InputStream input = new BufferedInputStream(Files.newInputStream(hxsPath));
                    OutputStream raw = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                     ZstdOutputStream output = new ZstdOutputStream(raw).setLevel(compressionLevel)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        copied = checkedAdd(copied, read);
                        if (copied > uncompressedSize) {
                            throw new IOException("verified HXS is larger than declared size");
                        }
                        hxsDigest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                if (copied != uncompressedSize || !MessageDigest.isEqual(hxsDigest.digest(), hxsFileHash.bytes())) {
                    throw new IOException("verified HXS digest or size changed");
                }
                force(temporary);
                return install(temporary, finalPath, trustedSnapshotId, uncompressedSize, hxsFileHash);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            release(trustedSnapshotId.value(), lock);
        }
    }

    @Override
    public StoredSourceArtifact storeCompressed(Path compressedPath, Sha256Id trustedSnapshotId,
                                                 long uncompressedSize, Sha256Digest hxsFileHash)
            throws IOException {
        ArtifactLock lock = acquire(trustedSnapshotId.value());
        try {
            SourceArtifactPath.requireRegularFile(compressedPath, "uploaded zstd payload");
            Path finalPath = paths.artifactPath(trustedSnapshotId);
            paths.createArtifactParent(finalPath);
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
                return existing(finalPath, trustedSnapshotId, uncompressedSize, hxsFileHash);
            }

            Path temporary = temporary(finalPath);
            try {
                copyAndForce(compressedPath, temporary);
                return install(temporary, finalPath, trustedSnapshotId, uncompressedSize, hxsFileHash);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            release(trustedSnapshotId.value(), lock);
        }
    }

    public StoredSourceArtifact storeHxs(Path hxsPath, String trustedSnapshotId,
                                          long uncompressedSize, byte[] hxsFileHash,
                                          int compressionLevel) throws IOException {
        return storeHxs(hxsPath, Sha256Id.parse(trustedSnapshotId), uncompressedSize,
                Sha256Digest.of(hxsFileHash), compressionLevel);
    }

    public StoredSourceArtifact storeCompressed(Path compressedPath, String trustedSnapshotId,
                                                 long uncompressedSize, byte[] hxsFileHash)
            throws IOException {
        return storeCompressed(compressedPath, Sha256Id.parse(trustedSnapshotId),
                uncompressedSize, Sha256Digest.of(hxsFileHash));
    }

    private static ArtifactLock acquire(String snapshotId) {
        ArtifactLock lock = ARTIFACT_LOCKS.compute(snapshotId, (ignored, current) -> {
            ArtifactLock value = current == null ? new ArtifactLock() : current;
            value.references++;
            return value;
        });
        lock.lock.lock();
        return lock;
    }

    private static void release(String snapshotId, ArtifactLock lock) {
        lock.lock.unlock();
        ARTIFACT_LOCKS.computeIfPresent(snapshotId, (ignored, current) -> {
            if (current != lock) {
                return current;
            }
            lock.references--;
            return lock.references == 0 && !lock.lock.isLocked()
                    && !lock.lock.hasQueuedThreads() ? null : lock;
        });
    }

    private StoredSourceArtifact install(Path temporary, Path finalPath, Sha256Id snapshotId,
                                         long uncompressedSize, Sha256Digest hxsFileHash)
            throws IOException {
        try {
            try {
                Files.move(temporary, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, finalPath);
            }
        } catch (FileAlreadyExistsException exception) {
            return existing(finalPath, snapshotId, uncompressedSize, hxsFileHash);
        }
        force(finalPath);
        return describe(finalPath, snapshotId, uncompressedSize, hxsFileHash);
    }

    private StoredSourceArtifact existing(Path finalPath, Sha256Id snapshotId,
                                          long uncompressedSize, Sha256Digest hxsFileHash)
            throws IOException {
        return describe(finalPath, snapshotId, uncompressedSize, hxsFileHash);
    }

    private StoredSourceArtifact describe(Path finalPath, Sha256Id snapshotId,
                                          long uncompressedSize, Sha256Digest hxsFileHash)
            throws IOException {
        SourceArtifactPath.requireRegularFile(finalPath, "permanent artifact");
        SourceCompression.FileDigest decompressed = SourceCompression.digestDecompressed(
                finalPath, uncompressedSize, uncompressedSize);
        if (!MessageDigest.isEqual(decompressed.hash(), hxsFileHash.bytes())) {
            throw new IOException("permanent artifact does not match verified HXS");
        }
        SourceCompression.FileDigest artifact = SourceCompression.digestFile(finalPath,
                Long.MAX_VALUE);
        return new StoredSourceArtifact(paths.storageKey(snapshotId), decompressed.size(),
                artifact.size(), hxsFileHash, Sha256Digest.of(artifact.hash()));
    }

    private static Path temporary(Path finalPath) throws IOException {
        return Files.createTempFile(finalPath.getParent(), "." + finalPath.getFileName() + ".",
                ".tmp");
    }

    private static void copyAndForce(Path source, Path target) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source));
            OutputStream output = new BufferedOutputStream(Files.newOutputStream(target,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        force(target);
    }

    private static void force(Path path) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long checkedAdd(long left, long right) throws IOException {
        if (right < 0 || Long.MAX_VALUE - left < right) {
            throw new IOException("managed file size overflow");
        }
        return left + right;
    }

    private static final class ArtifactLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
