package com.harmoniasuite.db;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Uuid6Test {

    @Test
    @DisplayName("формат v6: версия, вариант, длина")
    void versionAndVariant() {
        String id = Uuid6.generate();
        assertEquals(36, id.length());
        assertEquals('6', id.charAt(14));
        assertTrue("89ab".indexOf(id.charAt(19)) >= 0);
        UUID parsed = UUID.fromString(id);
        assertEquals(6, parsed.version());
        assertEquals(2, parsed.variant());
    }

    @Test
    @DisplayName("генерация уникальна, метка времени неубывающая")
    void uniqueAndOrdered() {
        Set<String> seen = new HashSet<>();
        String prev = "";
        for (int i = 0; i < 1000; i++) {
            String id = Uuid6.generate();
            assertTrue(seen.add(id));
            String time = id.substring(0, 8) + id.substring(9, 13) + id.substring(15, 18);
            assertTrue(time.compareTo(prev) >= 0);
            prev = time;
        }
    }
}
