package com.harmoniasuite.service.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.domain.PackAuthor;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.domain.TranslationDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PackManifestService {

    public static final String MANIFEST_FILE_NAME = "manifest.json";
    public static final int MANIFEST_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public PackMeta effectivePack(TranslationDocument document, String fallbackId) {
        return effectivePack(document == null ? null : document.getPack(), fallbackId);
    }

    public PackMeta effectivePack(PackMeta stored, String fallbackId) {
        PackMeta pack = stored != null ? stored : new PackMeta();
        if (isBlank(pack.getPackId()) && fallbackId != null && !fallbackId.isBlank()) {
            pack.setPackId(fallbackId.trim());
        }
        if (pack.getLanguages() == null || pack.getLanguages().isEmpty()) {
            pack.setLanguages(new ArrayList<>(List.of("ru")));
        }
        if (pack.getAuthors() == null) {
            pack.setAuthors(new ArrayList<>());
        }
        if (pack.getCompatibleGameVersions() == null) {
            pack.setCompatibleGameVersions(new ArrayList<>());
        }
        return pack;
    }

    public PackMeta storedView(PackMeta stored) {
        PackMeta pack = stored != null ? stored : new PackMeta();
        if (pack.getLanguages() == null || pack.getLanguages().isEmpty()) {
            pack.setLanguages(new ArrayList<>(List.of("ru")));
        }
        if (pack.getAuthors() == null) {
            pack.setAuthors(new ArrayList<>());
        }
        if (pack.getCompatibleGameVersions() == null) {
            pack.setCompatibleGameVersions(new ArrayList<>());
        }
        return pack;
    }

    public List<String> validate(PackMeta pack) {
        List<String> errors = new ArrayList<>();
        if (pack == null) {
            errors.add("Настройки пака не заполнены (вкладка «Пак»).");
            return errors;
        }
        String id = trim(pack.getPackId());
        if (id.isEmpty()) {
            errors.add("packId обязателен.");
        } else if (!isSlug(id)) {
            errors.add("packId: только буквы, цифры, «-» и «_», без пробелов.");
        }
        if (trim(pack.getTranslationVersion()).isEmpty()) {
            errors.add("translationVersion обязателен (например 1.0.0).");
        }
        if (trim(pack.getGameVersion()).isEmpty()) {
            errors.add("gameVersion обязателен (например 2026.08.11.0000.0000).");
        }
        if (trim(pack.getVendorId()).isEmpty() || trim(pack.getVendorName()).isEmpty()) {
            errors.add("vendor: обязательны id и name.");
        }
        List<PackAuthor> authors = pack.getAuthors() == null
                ? List.of()
                : pack.getAuthors().stream().filter(a -> a != null && !trim(a.getName()).isEmpty()).toList();
        if (authors.isEmpty()) {
            errors.add("authors: нужен хотя бы один автор с именем.");
        }
        return errors;
    }

    public Map<String, Object> build(PackMeta pack) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("id", trim(pack.getPackId()));
        manifest.put("translationVersion", trim(pack.getTranslationVersion()));
        manifest.put("gameVersion", trim(pack.getGameVersion()));
        List<String> compat = pack.getCompatibleGameVersions() == null ? List.of() : pack.getCompatibleGameVersions().stream()
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
        if (!compat.isEmpty()) {
            manifest.put("compatibleGameVersions", compat);
        }
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("id", trim(pack.getVendorId()));
        vendor.put("name", trim(pack.getVendorName()));
        putIfPresent(vendor, "url", pack.getVendorUrl());
        putIfPresent(vendor, "contact", pack.getVendorContact());
        manifest.put("vendor", vendor);
        List<Map<String, Object>> authors = new ArrayList<>();
        if (pack.getAuthors() != null) {
            for (PackAuthor author : pack.getAuthors()) {
                if (author == null || trim(author.getName()).isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", trim(author.getName()));
                putIfPresent(entry, "role", author.getRole());
                putIfPresent(entry, "contact", author.getContact());
                authors.add(entry);
            }
        }
        manifest.put("authors", authors);
        List<String> languages = pack.getLanguages() == null ? List.of() : pack.getLanguages().stream()
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
        if (!languages.isEmpty()) {
            manifest.put("languages", languages);
        }
        putIfPresent(manifest, "title", pack.getTitle());
        putIfPresent(manifest, "description", pack.getDescription());
        putIfPresent(manifest, "changelog", pack.getChangelog());
        putIfPresent(manifest, "homepage", pack.getHomepage());
        putIfPresent(manifest, "license", pack.getLicense());
        putIfPresent(manifest, "minPluginVersion", pack.getMinPluginVersion());
        return manifest;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSlug(String value) {
        return value.matches("[A-Za-z0-9_-]+");
    }

    public byte[] toJsonBytes(Map<String, Object> manifest) throws IOException {
        byte[] raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        String text = new String(raw, StandardCharsets.UTF_8).stripTrailing() + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public void write(Path outputRoot, Map<String, Object> manifest) throws IOException {
        Files.createDirectories(outputRoot);
        Path target = outputRoot.resolve(MANIFEST_FILE_NAME);
        Path temp = Files.createTempFile(outputRoot, ".manifest.", ".tmp");
        try {
            Files.write(temp, toJsonBytes(manifest));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }
}
