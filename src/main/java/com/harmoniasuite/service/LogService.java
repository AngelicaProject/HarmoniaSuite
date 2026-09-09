package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class LogService {

    static final int DEFAULT_TAIL = 500;
    static final int MAX_TAIL = 2000;
    static final int MAX_BYTES = 1024 * 1024;

    private final Path logFile;

    @Autowired
    public LogService(WorkspacePaths workspace) {
        this(workspace.resolve("logs/harmonia.log"));
    }

    LogService(Path logFile) {
        this.logFile = logFile;
    }

    public String tail(int requested) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            throw new HarmoniaSuiteNotFoundException("Журнал приложения ещё не создан");
        }
        long size = Files.size(logFile);
        long start = Math.max(0, size - MAX_BYTES);
        byte[] bytes = new byte[(int) Math.min(size - start, MAX_BYTES)];
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            file.seek(start);
            file.readFully(bytes);
        }
        return tailText(new String(bytes, StandardCharsets.UTF_8), normalizeTail(requested), start > 0);
    }

    static int normalizeTail(int requested) {
        if (requested <= 0) {
            return DEFAULT_TAIL;
        }
        return Math.min(requested, MAX_TAIL);
    }

    static String tailText(String text, int requested, boolean partialFirstLine) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<String> lines = text.lines().toList();
        int first = partialFirstLine && lines.size() > 1 ? 1 : 0;
        int count = Math.min(Math.max(requested, 1), MAX_TAIL);
        int start = Math.max(first, lines.size() - count);
        String output = String.join("\n", lines.subList(start, lines.size()));
        if (!output.isEmpty() && (text.endsWith("\n") || text.endsWith("\r"))) {
            output += "\n";
        }
        return output;
    }
}
