package com.harmoniasuite.source.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCompressionTest {

    @Test
    void decompressesValidZstdWithExactBoundedSize(@TempDir Path directory) throws Exception {
        byte[] source = new byte[]{1, 2, 3, 4, 5};
        Path compressed = compress(directory.resolve("source.zst"), source);
        Path output = directory.resolve("source.hxs");

        SourceCompression.decompressExact(compressed, output, source.length, source.length);

        assertArrayEquals(source, Files.readAllBytes(output));
        assertThrows(IOException.class, () -> SourceCompression.decompressExact(
                compressed, directory.resolve("too-small.hxs"), 4, 10));
        assertThrows(IOException.class, () -> SourceCompression.decompressExact(
                compressed, directory.resolve("too-large.hxs"), 6, 10));
    }

    @Test
    void rejectsInvalidZstdAndConfiguredMaximum(@TempDir Path directory) throws Exception {
        Path invalid = directory.resolve("invalid.zst");
        Files.write(invalid, new byte[]{9, 8, 7});
        assertThrows(IOException.class, () -> SourceCompression.decompressExact(
                invalid, directory.resolve("invalid.hxs"), 3, 3));

        byte[] source = new byte[]{1, 2, 3, 4, 5};
        Path compressed = compress(directory.resolve("source.zst"), source);
        assertThrows(IOException.class, () -> SourceCompression.decompressExact(
                compressed, directory.resolve("limited.hxs"), source.length, 4));
    }

    private static Path compress(Path target, byte[] source) throws IOException {
        try (OutputStream raw = Files.newOutputStream(target);
             ZstdOutputStream output = new ZstdOutputStream(raw)) {
            output.write(source);
        }
        return target;
    }
}
