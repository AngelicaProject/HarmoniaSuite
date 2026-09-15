package com.harmoniasuite.source.artifact;

import com.github.luben.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded streaming operations used by both transport verification and artifact storage. */
public final class SourceCompression {

    private static final int BUFFER_SIZE = 64 * 1024;

    private SourceCompression() {
    }

    public record FileDigest(long size, byte[] hash) {
    }

    public static FileDigest digestFile(Path path, long maximumBytes) throws IOException {
        requireRegular(path);
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = checkedAdd(total, read);
                if (total > maximumBytes) {
                    throw new IOException("managed file exceeds configured limit");
                }
                digest.update(buffer, 0, read);
            }
        }
        return new FileDigest(total, digest.digest());
    }

    public static FileDigest decompressExact(Path compressedPath, Path outputPath,
                                             long expectedSize, long maximumSize)
            throws IOException {
        requireRegular(compressedPath);
        if (expectedSize < 0 || maximumSize < 0 || expectedSize > maximumSize) {
            throw new IOException("declared HXS size exceeds configured limit");
        }
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(compressedPath));
             ZstdInputStream input = new ZstdInputStream(raw);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputPath,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = checkedAdd(total, read);
                if (total > expectedSize || total > maximumSize) {
                    throw new IOException("decompressed HXS exceeds declared size");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException exception) {
            throw new IOException("zstd decompression failed", exception);
        }
        if (total != expectedSize) {
            throw new IOException("decompressed HXS size does not match declaration");
        }
        return new FileDigest(total, digest.digest());
    }

    public static FileDigest digestDecompressed(Path compressedPath, long expectedSize,
                                                long maximumSize) throws IOException {
        requireRegular(compressedPath);
        if (expectedSize < 0 || maximumSize < 0 || expectedSize > maximumSize) {
            throw new IOException("declared HXS size exceeds configured limit");
        }
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(compressedPath));
             ZstdInputStream input = new ZstdInputStream(raw)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = checkedAdd(total, read);
                if (total > expectedSize || total > maximumSize) {
                    throw new IOException("stored artifact exceeds expected size");
                }
                digest.update(buffer, 0, read);
            }
        } catch (RuntimeException exception) {
            throw new IOException("stored zstd artifact could not be read", exception);
        }
        if (total != expectedSize) {
            throw new IOException("stored artifact size does not match expected HXS size");
        }
        return new FileDigest(total, digest.digest());
    }

    private static void requireRegular(Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed file is not a regular file");
        }
    }

    private static long checkedAdd(long left, long right) throws IOException {
        if (right < 0 || Long.MAX_VALUE - left < right) {
            throw new IOException("managed file size overflow");
        }
        return left + right;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
