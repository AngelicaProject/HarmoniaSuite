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
        byte[] ico = ico(png(16, 0xFF334455), png(32, 0xFF667788));
        BufferedImage image = IcoSupport.readBest(ico, 16, 16);
        assertEquals(16, image.getWidth());
        assertEquals(0xFF334455, image.getRGB(0, 0));
    }

    @Test
    @DisplayName("smallest larger entry wins without exact match")
    void picksSmallestLargerEntry() throws IOException {
        byte[] ico = ico(png(48, 0xFF334455), png(32, 0xFF667788));
        assertEquals(32, IcoSupport.readBest(ico, 16, 16).getWidth());
    }

    @Test
    @DisplayName("largest entry wins when all are smaller")
    void picksLargestSmallerEntry() throws IOException {
        byte[] ico = ico(png(16, 0xFF334455));
        assertEquals(16, IcoSupport.readBest(ico, 32, 32).getWidth());
    }

    @Test
    @DisplayName("non-icon bytes are rejected")
    void rejectsNonIconBytes() {
        assertThrows(IOException.class, () -> IcoSupport.readBest(new byte[]{1, 2, 3, 4, 5, 6}, 16, 16));
    }

    private static byte[] ico(byte[]... images) {
        ByteBuffer buf = ByteBuffer.allocate(6 + images.length * 16 + total(images)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) images.length);
        int offset = 6 + images.length * 16;
        for (byte[] image : images) {
            int size = sideOf(image);
            buf.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(image.length).putInt(offset);
            offset += image.length;
        }
        for (byte[] image : images) {
            buf.put(image);
        }
        return buf.array();
    }

    private static int sideOf(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
            return image.getWidth();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int total(byte[][] images) {
        int total = 0;
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
}
