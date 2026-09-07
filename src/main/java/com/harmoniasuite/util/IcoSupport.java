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
        List<Entry> entries = dir(ico);
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

    private static List<Entry> dir(byte[] ico) throws IOException {
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
        return ImageIO.read(new ByteArrayInputStream(wrapBmp(raw)));
    }

    private static boolean isPng(byte[] raw) {
        return raw.length > 8 && raw[0] == (byte) 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G';
    }

    private static byte[] wrapBmp(byte[] dib) throws IOException {
        if (dib.length < 40) {
            throw new IOException("too small for DIB");
        }
        ByteBuffer buf = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = buf.getInt(0);
        int bitCount = buf.getShort(14) & 0xFFFF;
        int colors = buf.getInt(32);
        int palette = (colors != 0 ? colors : bitCount <= 8 ? 1 << bitCount : 0) * 4;
        byte[] bmp = new byte[14 + dib.length];
        ByteBuffer out = ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'B').put((byte) 'M');
        out.putInt(bmp.length);
        out.putInt(0);
        out.putInt(14 + headerSize + palette);
        System.arraycopy(dib, 0, bmp, 14, dib.length);
        return bmp;
    }
}
