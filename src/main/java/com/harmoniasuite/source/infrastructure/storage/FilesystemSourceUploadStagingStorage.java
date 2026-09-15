package com.harmoniasuite.source.infrastructure.storage;

import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.application.model.PreparedSourceUpload;
import com.harmoniasuite.source.application.exception.SourceUploadTooLargeException;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Safe filesystem adapter for upload staging and durable chunk appends. */
public final class FilesystemSourceUploadStagingStorage implements SourceUploadStagingStorage {

    private static final int BUFFER_SIZE = 64 * 1024;
    private final SourceArtifactPath paths;

    public FilesystemSourceUploadStagingStorage(SourceArtifactPath paths) {
        this.paths = paths;
    }

    @Override
    public void create(UUID uploadId) throws IOException {
        paths.createStagingDirectory(uploadId.toString());
    }

    @Override
    public long receivedBytes(UUID uploadId) throws IOException {
        Path payload = payload(uploadId);
        if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        SourceArtifactPath.requireRegularFile(payload, "uploaded payload");
        return Files.size(payload);
    }

    @Override
    public boolean exists(UUID uploadId) {
        Path directory = directory(uploadId);
        Path payload = payload(uploadId);
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(directory)
                && Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(payload);
    }

    @Override
    public long append(UUID uploadId, InputStream body, long contentLength,
                       long maxChunkBytes, long declaredUploadSize) throws IOException {
        Path temporary = paths.chunkTempPath(uploadId.toString());
        deleteFileIfPresent(temporary);
        long copied = copyBounded(body, temporary, maxChunkBytes);
        if (copied != contentLength) {
            throw new IllegalArgumentException("request body does not match Content-Length");
        }
        Path target = payload(uploadId);
        if (copied > declaredUploadSize - receivedBytes(uploadId)) {
            throw new SourceUploadTooLargeException("chunk exceeds declared upload size");
        }
        appendFile(temporary, target);
        long newOffset = Files.size(target);
        if (newOffset > declaredUploadSize) {
            throw new SourceUploadTooLargeException("upload exceeds declared size");
        }
        deleteFileIfPresent(temporary);
        return newOffset;
    }

    @Override
    public void delete(UUID uploadId) throws IOException {
        deleteTree(paths.uploadDirectory(uploadId.toString()));
    }

    @Override
    public Path directory(UUID uploadId) { return paths.uploadDirectory(uploadId.toString()); }
    @Override
    public Path payload(UUID uploadId) { return paths.payloadPath(uploadId.toString()); }
    @Override
    public Path snapshot(UUID uploadId) { return paths.snapshotPath(uploadId.toString()); }
    @Override
    public Path snapshotTemp(UUID uploadId) { return paths.snapshotTempPath(uploadId.toString()); }

    @Override
    public void deleteFileIfPresent(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                SourceArtifactPath.requireRegularFile(path, "managed temporary file");
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("managed temporary file could not be deleted", exception);
        }
    }

    @Override
    public PreparedSourceUpload prepareForIngestion(UUID uploadId, TransportEncoding encoding,
                                                    long uploadSize, long uncompressedSize,
                                                    long maxUploadBytes, long maxHxsBytes) {
        Path payload = payload(uploadId);
        try {
            SourceCompression.FileDigest transport = SourceCompression.digestFile(payload, maxUploadBytes);
            if (transport.size() != uploadSize) {
                throw new IllegalArgumentException("uploaded transport size does not match declaration");
            }
            if (encoding == TransportEncoding.IDENTITY) {
                if (uploadSize != uncompressedSize) throw new IllegalArgumentException("identity upload sizes do not match");
                SourceCompression.FileDigest hxs = SourceCompression.digestFile(payload, maxHxsBytes);
                if (hxs.size() != uncompressedSize) throw new IllegalArgumentException("identity HXS size does not match declaration");
                return new PreparedSourceUpload(payload, payload, hxs.size(),
                        Sha256Digest.of(transport.hash()), Sha256Digest.of(hxs.hash()));
            }
            Path temporary = snapshotTemp(uploadId);
            deleteFileIfPresent(temporary);
            SourceCompression.FileDigest hxs = SourceCompression.decompressExact(payload, temporary,
                    uncompressedSize, maxHxsBytes);
            force(temporary);
            Path snapshotPath = snapshot(uploadId);
            deleteFileIfPresent(snapshotPath);
            try { Files.move(temporary, snapshotPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, snapshotPath); }
            return new PreparedSourceUpload(payload, snapshotPath, hxs.size(),
                    Sha256Digest.of(transport.hash()), Sha256Digest.of(hxs.hash()));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("uploaded source payload could not be prepared", exception);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static long copyBounded(InputStream input, Path target, long maximum) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > maximum) {
                    throw new SourceUploadTooLargeException("chunk exceeds configured limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (ArithmeticException exception) {
            throw new SourceUploadTooLargeException("chunk size overflow");
        }
        return total;
    }

    private static void appendFile(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            SourceArtifactPath.requireRegularFile(target, "uploaded payload");
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) output.write(bytes);
            }
            output.force(true);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed staging directory is not a directory");
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { throw new DeleteTreeRuntimeException(exception); }
            });
        } catch (DeleteTreeRuntimeException exception) {
            throw exception.exception;
        }
    }

    private static final class DeleteTreeRuntimeException extends RuntimeException {
        private final IOException exception;
        private DeleteTreeRuntimeException(IOException exception) { this.exception = exception; }
    }
}
