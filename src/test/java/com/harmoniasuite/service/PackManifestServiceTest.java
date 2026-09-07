package com.harmoniasuite.service;

import com.harmoniasuite.domain.PackAuthor;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.domain.TranslationDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManifestServiceTest {

    private final PackManifestService packs = new PackManifestService();

    @Test
    @DisplayName("validation lists all blank fields")
    void validateReportsAllMissingFields() {
        List<String> errors = packs.validate(new PackMeta());
        assertFalse(errors.isEmpty());
        String joined = String.join("\n", errors);
        assertTrue(joined.contains("packId"));
        assertTrue(joined.contains("translationVersion"));
        assertTrue(joined.contains("gameVersion"));
        assertTrue(joined.contains("vendor"));
        assertTrue(joined.contains("authors"));
    }

    @Test
    @DisplayName("invalid slug is rejected")
    void validateRejectsBadSlug() {
        PackMeta pack = validPack();
        pack.setPackId("bad id!");
        assertTrue(packs.validate(pack).stream().anyMatch(e -> e.contains("packId")));
    }

    @Test
    @DisplayName("effective pack defaults")
    void effectivePackAppliesDefaults() {
        TranslationDocument document = new TranslationDocument();
        document.setPack(new PackMeta());
        PackMeta pack = packs.effectivePack(document, "fallback-id");
        assertEquals("fallback-id", pack.getPackId());
        assertEquals(List.of("ru"), pack.getLanguages());
    }

    @Test
    @DisplayName("pack view keeps a blank id")
    void storedViewKeepsBlankId() {
        PackMeta stored = new PackMeta();
        stored.setPackId("  ");
        PackMeta pack = packs.storedView(stored);
        assertTrue(pack.getPackId() == null || pack.getPackId().isBlank());
        assertEquals(List.of("ru"), pack.getLanguages());
    }

    @Test
    @DisplayName("minimal valid manifest")
    void buildProducesMinimalManifest() throws Exception {
        Map<String, Object> manifest = packs.build(validPack());
        assertEquals(1, manifest.get("manifestVersion"));
        assertEquals("pack-one", manifest.get("id"));
        assertEquals("2026.08.11.0000.0000", manifest.get("gameVersion"));
        assertTrue(packs.toJsonBytes(manifest).length > 0);
        String json = new String(packs.toJsonBytes(manifest));
        assertTrue(json.endsWith("\n"));
    }

    private static PackMeta validPack() {
        PackMeta pack = new PackMeta();
        pack.setPackId("pack-one");
        pack.setTranslationVersion("1.0.0");
        pack.setGameVersion("2026.08.11.0000.0000");
        pack.setVendorId("vendor-one");
        pack.setVendorName("Vendor One");
        PackAuthor author = new PackAuthor();
        author.setName("Author One");
        pack.setAuthors(new ArrayList<>(List.of(author)));
        pack.setLanguages(new ArrayList<>(List.of("ru")));
        return pack;
    }
}
