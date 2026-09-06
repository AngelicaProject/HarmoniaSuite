package com.harmoniasuite.db;

import java.util.concurrent.ThreadLocalRandom;

public final class Uuid6 {

    private static final long GREGORIAN_OFFSET_100NS = 122192928000000000L;

    private Uuid6() {
    }

    public static String generate() {
        long timestamp = (System.currentTimeMillis() * 10_000L + GREGORIAN_OFFSET_100NS) & 0x0FFFFFFFFFFFFFFFL;
        long timeLow = timestamp >>> 28;
        long timeMid = (timestamp >>> 12) & 0xFFFFL;
        long timeHi = timestamp & 0xFFFL;
        long random = ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long clockSeq = ((random >>> 48) & 0x3FFFL) | 0x8000L;
        long node = random & 0xFFFFFFFFFFFFL;
        return String.format("%08x-%04x-6%03x-%04x-%012x", timeLow, timeMid, timeHi, clockSeq, node);
    }
}
