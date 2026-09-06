package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.SourceFilesDto;
import com.harmoniasuite.dto.SourcePreviewDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.util.CsvSupport;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FileSearchService {

    private static final int FULL_PREVIEW_ROWS = 500;

    private final WorkspacePaths workspace;
    private final CsvSupport csvSupport;

    public FileSearchService(WorkspacePaths workspace, CsvSupport csvSupport) {
        this.workspace = workspace;
        this.csvSupport = csvSupport;
    }

    public SourceFilesDto listCsvFiles(String rootValue) throws IOException {
        Path root = workspace.resolve(rootValue);
        if (!Files.isDirectory(root)) {
            throw new HarmoniaSuiteBadRequestException("Directory not found: " + root);
        }
        List<String> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .map(p -> relativize(root, p))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return new SourceFilesDto(files, root.toString());
    }

    public SourcePreviewDto previewFile(String rootValue, String file) throws IOException {
        return previewFile(rootValue, file, false);
    }

    public SourcePreviewDto previewFile(String rootValue, String file, boolean full) throws IOException {
        Path rootPath = workspace.resolve(rootValue);
        Path p = rootPath.resolve(file).normalize();
        if (!p.startsWith(rootPath) || !p.getFileName().toString().toLowerCase().endsWith(".csv")) {
            throw new HarmoniaSuiteBadRequestException("invalid file");
        }
        if (!Files.isRegularFile(p)) {
            throw new FileNotFoundException("not found");
        }
        List<List<String>> rows = csvSupport.readRows(p);
        List<Integer> cols = csvSupport.stringColumns(rows);
        List<List<String>> preview = rows.stream().skip(CsvSupport.DATA_START_ROW).limit(8)
                .map(r -> cols.stream().map(c -> c < r.size() ? r.get(c) : "").filter(s -> !s.isBlank()).toList())
                .filter(l -> !l.isEmpty()).limit(8).toList();
        long translatable = rows.stream().skip(CsvSupport.DATA_START_ROW)
                .flatMap(r -> cols.stream().map(c -> c < r.size() ? r.get(c) : ""))
                .filter(csvSupport::isTranslatable).count();
        List<List<String>> head = null;
        List<List<String>> data = null;
        Boolean truncated = null;
        if (full) {
            int headEnd = Math.min(CsvSupport.DATA_START_ROW, rows.size());
            head = List.copyOf(rows.subList(0, headEnd));
            int dataFrom = Math.min(rows.size(), CsvSupport.DATA_START_ROW);
            int dataEnd = Math.min(rows.size(), dataFrom + FULL_PREVIEW_ROWS);
            data = List.copyOf(rows.subList(dataFrom, dataEnd));
            truncated = rows.size() > dataEnd;
        }
        return new SourcePreviewDto(file, rows.size(), cols, translatable, preview, head, data, truncated);
    }

    private String relativize(Path root, Path path) {
        try {
            return root.relativize(path.toAbsolutePath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return path.getFileName().toString();
        }
    }
}
