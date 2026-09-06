package com.harmoniasuite.service;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.SourcePreviewDto;
import com.harmoniasuite.util.CsvSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSearchServicePreviewTest {

    @TempDir
    Path workspace;

    private FileSearchService files;

    @BeforeEach
    void setUp() throws Exception {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        files = new FileSearchService(new WorkspacePaths(properties), new CsvSupport());
        Path root = workspace.resolve("rawexd/en");
        Files.createDirectories(root);
        String csv = String.join("\n",
                List.of("key,#,offset", "0,0,0", "1,1,1", "Int32,String,String",
                        "0,First line,Second line", "1,Another line,Last line"));
        Files.writeString(root.resolve("pack-one.csv"), csv, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Краткое превью не возвращает полную таблицу")
    void shortPreviewHasNoFullTable() throws Exception {
        SourcePreviewDto res = files.previewFile("rawexd/en", "pack-one.csv");
        assertFalse(res.preview().isEmpty());
        assertNull(res.data());
        assertNull(res.head());
    }

    @Test
    @DisplayName("Полное превью возвращает шапку и все строки данных")
    void fullPreviewReturnsHeadAndData() throws Exception {
        SourcePreviewDto res = files.previewFile("rawexd/en", "pack-one.csv", true);
        assertEquals(List.of("key,#,offset", "0,0,0", "1,1,1", "Int32,String,String"),
                res.head().stream().map(r -> String.join(",", r)).toList());
        assertEquals(2, res.data().size());
        assertEquals(false, res.truncated());
    }

    @Test
    @DisplayName("Список исходников отдаёт только имена csv")
    void sourceFilesListsCsvNames() throws Exception {
        assertEquals(List.of("pack-one.csv"), files.listCsvFiles("rawexd/en").files());
    }
}
