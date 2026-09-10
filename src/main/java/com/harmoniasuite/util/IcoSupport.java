package com.harmoniasuite.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

public final class IcoSupport {

    private IcoSupport() {
    }

    public static BufferedImage readBest(byte[] ico, int width, int height) throws IOException {
        List<Entry> entries = readDirectory(ico);
        entries.sort(Comparator.comparingInt(e -> score(e.size(), Math.max(16, width))));
        IOException failed = new IOException("no decodable image in ICO");
        for (Entry entry : entries) {
            try {
                BufferedImage image = decode(ico, entry);
                if (image != null) {
                    return image;
                }
            } catch (IOException e) {
                failed = e;
            }
        }
        throw failed;
    }

    private static int score(int size, int target) {
        if (size == target) {
            return 0;
        }
        return size > target ? size - target : Integer.MAX_VALUE / 2 + (target - size);
    }

    private record Entry(int size, int offset, int length) {
    }

    private static List<Entry> readDirectory(byte[] ico) throws IOException {
        if (ico.length < 6) {
            throw new IOException("too small for ICO header");
        }
        ByteBuffer buf = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getShort() != 0 || buf.getShort() != 1) {
            throw new IOException("not an ICO file");
        }
        int count = buf.getShort() & 0xFFFF;
        if (count == 0 || 6 + count * 16 > ico.length) {
            throw new IOException("broken ICO directory");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            int size = ico[base] & 0xFF;
            int length = buf.getInt(base + 8);
            int offset = buf.getInt(base + 12);
            if (length <= 0 || offset < 0 || offset + length > ico.length) {
                throw new IOException("broken ICO entry");
            }
            entries.add(new Entry(size == 0 ? 256 : size, offset, length));
        }
        return entries;
    }

    private static BufferedImage decode(byte[] ico, Entry entry) throws IOException {
        byte[] raw = new byte[entry.length()];
        System.arraycopy(ico, entry.offset(), raw, 0, entry.length());
        if (isPng(raw)) {
            return ImageIO.read(new ByteArrayInputStream(raw));
        }
        return dibToImage(raw);
    }

    private static boolean isPng(byte[] raw) {
        return raw.length > 8 && raw[0] == (byte) 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G';
    }

    private static BufferedImage dibToImage(byte[] dib) throws IOException {
        if (dib.length < 40) {
            throw new IOException("too small for DIB");
        }
        ByteBuffer buf = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int width = buf.getInt(4);
        int fullHeight = buf.getInt(8);
        int bitCount = buf.getShort(14) & 0xFFFF;
        int compression = buf.getInt(16);
        if (width <= 0 || width > 256 || bitCount != 32 || compression != 0) {
            throw new IOException("unsupported DIB");
        }
        int height = Math.abs(fullHeight);
        boolean bottomUp = fullHeight > 0;
        int stride = ((width + 31) / 32) * 4;
        int pixels = height / 2;
        boolean masked = height % 2 == 0
                && dib.length >= 40L + (long) width * pixels * 4 + (long) stride * pixels;
        if (!masked) {
            pixels = height;
            if (dib.length < 40L + (long) width * pixels * 4) {
                throw new IOException("truncated DIB");
            }
        }
        BufferedImage image = new BufferedImage(width, pixels, BufferedImage.TYPE_INT_ARGB);
        int maskBase = 40 + width * pixels * 4;
        for (int y = 0; y < pixels; y++) {
            int row = bottomUp ? pixels - 1 - y : y;
            for (int x = 0; x < width; x++) {
                int at = 40 + (row * width + x) * 4;
                int alpha = dib[at + 3] & 0xFF;
                if (masked) {
                    int bit = (dib[maskBase + row * stride + x / 8] >> (7 - x % 8)) & 1;
                    alpha = bit == 1 ? 0 : alpha == 0 ? 0xFF : alpha;
                } else if (alpha == 0) {
                    alpha = 0xFF;
                }
                int argb = alpha << 24 | (dib[at + 2] & 0xFF) << 16 | (dib[at + 1] & 0xFF) << 8 | dib[at] & 0xFF;
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
}
