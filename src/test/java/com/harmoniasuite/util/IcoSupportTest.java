package com.harmoniasuite.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IcoSupportTest {

    @Test
    @DisplayName("exact-size entry is picked for the tray")
    void picksExactSizeEntry() throws IOException {
        byte[] ico = ico(new int[]{16, 32}, png(16, 0xFF334455), png(32, 0xFF667788));
        BufferedImage image = IcoSupport.readBest(ico, 16, 16);
        assertEquals(16, image.getWidth());
        assertEquals(0xFF334455, image.getRGB(0, 0));
    }

    @Test
    @DisplayName("smallest larger entry wins without exact match")
    void picksSmallestLargerEntry() throws IOException {
        byte[] ico = ico(new int[]{48, 32}, png(48, 0xFF334455), png(32, 0xFF667788));
        assertEquals(32, IcoSupport.readBest(ico, 16, 16).getWidth());
    }

    @Test
    @DisplayName("largest entry wins when all are smaller")
    void picksLargestSmallerEntry() throws IOException {
        byte[] ico = ico(new int[]{16}, png(16, 0xFF334455));
        assertEquals(16, IcoSupport.readBest(ico, 32, 32).getWidth());
    }

    @Test
    @DisplayName("dib entry decodes with mask transparency")
    void decodesDibWithMask() throws IOException {
        byte[] ico = ico(new int[]{2}, dib(2, new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF},
                new boolean[]{false, true, false, false}));
        BufferedImage image = IcoSupport.readBest(ico, 16, 16);
        assertEquals(2, image.getWidth());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0, image.getRGB(1, 0) >>> 24);
        assertEquals(0xFF0000FF, image.getRGB(0, 1));
    }

    @Test
    @DisplayName("dib entries compete by size")
    void picksDibBySize() throws IOException {
        byte[] ico = ico(new int[]{32, 16}, dib(32, solid(32, 0xFF334455), solid(32, false)),
                dib(16, solid(16, 0xFF667788), solid(16, false)));
        BufferedImage image = IcoSupport.readBest(ico, 16, 16);
        assertEquals(16, image.getWidth());
        assertEquals(0xFF667788, image.getRGB(0, 0));
    }

    @Test
    @DisplayName("non-icon bytes are rejected")
    void rejectsNonIconBytes() {
        assertThrows(IOException.class, () -> IcoSupport.readBest(new byte[]{1, 2, 3, 4, 5, 6}, 16, 16));
    }

    private static byte[] ico(int[] sides, byte[]... images) {
        ByteBuffer buf = ByteBuffer.allocate(6 + images.length * 16 + total(images)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) images.length);
        int offset = 6 + images.length * 16;
        for (int i = 0; i < images.length; i++) {
            buf.put((byte) sides[i]).put((byte) sides[i]).put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] image : images) {
            buf.put(image);
        }
        return buf.array();
    }

    private static int total(byte[][] images) {        int total = 0;
        for (byte[] image : images) {
            total += image.length;
        }
        return total;
    }

    private static byte[] png(int side, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int[] solid(int side, int rgb) {
        int[] pixels = new int[side * side];
        java.util.Arrays.fill(pixels, rgb);
        return pixels;
    }

    private static boolean[] solid(int side, boolean transparent) {
        boolean[] mask = new boolean[side * side];
        java.util.Arrays.fill(mask, transparent);
        return mask;
    }

    private static byte[] dib(int side, int[] argb, boolean[] transparent) {
        int stride = ((side + 31) / 32) * 4;
        ByteBuffer buf = ByteBuffer.allocate(40 + side * side * 4 + stride * side).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(40).putInt(side).putInt(side * 2).putShort((short) 1).putShort((short) 32);
        buf.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = side - 1; y >= 0; y--) {
            for (int x = 0; x < side; x++) {
                int pixel = argb[y * side + x];
                buf.put((byte) pixel).put((byte) (pixel >> 8)).put((byte) (pixel >> 16)).put((byte) 0);
            }
        }
        for (int y = side - 1; y >= 0; y--) {
            int bits = 0;
            for (int x = 0; x < side; x++) {
                if (transparent[y * side + x]) {
                    bits |= 0x80 >> (x % 8);
                }
                if (x % 8 == 7) {
                    buf.put((byte) bits);
                    bits = 0;
                }
            }
            if (side % 8 != 0) {
                buf.put((byte) bits);
            }
            for (int p = (side + 7) / 8; p < stride; p++) {
                buf.put((byte) 0);
            }
        }
        return buf.array();
    }
}
