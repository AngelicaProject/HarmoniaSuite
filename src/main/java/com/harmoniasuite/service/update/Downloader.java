package com.harmoniasuite.service.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.exception.UpdateErrorCode;
import com.harmoniasuite.exception.UpdateException;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fetches the archives self-update installs from; only allowlisted hosts are trusted. */
final class Downloader {

    private static final Set<String> ALLOWED_DOWNLOAD_HOSTS = Set.of(
            "api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "api.adoptium.net");
    private static final int MAX_BODY_EXCERPT_CHARS = 4096;
    private static final Logger logger = LoggerFactory.getLogger(Downloader.class);

    private Downloader() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fetchJson(ObjectMapper objectMapper, String url) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            URI source = requireAllowedDownloadUri(url);
            HttpRequest request = HttpRequest.newBuilder(source)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "harmonia-suite")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException(httpFailureMessage("HTTP", response.statusCode(), response.body()));
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.INTERRUPTED, e);
        }
    }

    static void downloadAndExtract(String url, Path directory, String tempPrefix,
            Consumer<String> log, UpdateErrorCode downloadFailureCode) {
        Path archive;
        try {
            archive = Files.createTempFile(tempPrefix, ".zip");
        } catch (IOException e) {
            throw new UpdateException(downloadFailureCode, UpdateProcess.diagnosticDetail(e), e);
        }
        try {
            downloadToFile(url, archive, log);
            try {
                extractZip(archive, directory, log);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT,
                        UpdateProcess.diagnosticDetail(e), e);
            }
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(downloadFailureCode, UpdateProcess.diagnosticDetail(e), e);
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException e) {
                logger.debug("Не удалось удалить временный архив обновления", e);
            }
        }
    }

    private static void downloadToFile(String url, Path target, Consumer<String> log) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            URI source = requireAllowedDownloadUri(url);
            HttpRequest request = HttpRequest.newBuilder(source)
                    .header("User-Agent", "harmonia-suite")
                    .timeout(Duration.ofSeconds(600))
                    .GET()
                    .build();
            HttpResponse<Path> response =
                    http.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException(httpFailureMessage("скачивание: HTTP", response.statusCode(),
                        readBodyExcerpt(target)));
            }
            if (!isAllowedDownloadUri(response.uri())) {
                throw new IOException("download redirect host is not allowed");
            }
            log.accept("Скачано " + Files.size(target) / 1024 / 1024 + " МБ");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.INTERRUPTED, e);
        }
    }

    private static URI requireAllowedDownloadUri(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            if (!isAllowedDownloadUri(uri)) {
                throw new IOException("download host is not allowed");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("download URL is invalid", e);
        }
    }

    private static boolean isAllowedDownloadUri(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && ALLOWED_DOWNLOAD_HOSTS.contains(uri.getHost());
    }

    private static void extractZip(Path zip, Path directory, Consumer<String> log) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException("zip-slip");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (var in = zipFile.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.accept("Распаковано в " + directory);
    }

    private static String httpFailureMessage(String prefix, int status, String body) {
        String excerpt = truncateBody(body);
        return prefix + " " + status + (excerpt.isBlank() ? "" : ": " + excerpt);
    }

    private static String readBodyExcerpt(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder value = new StringBuilder(MAX_BODY_EXCERPT_CHARS + 1);
            char[] buffer = new char[Math.min(1024, MAX_BODY_EXCERPT_CHARS + 1)];
            while (value.length() <= MAX_BODY_EXCERPT_CHARS) {
                int count = reader.read(buffer, 0,
                        Math.min(buffer.length, MAX_BODY_EXCERPT_CHARS + 1 - value.length()));
                if (count < 0) {
                    break;
                }
                value.append(buffer, 0, count);
            }
            return truncateBody(value.toString());
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncateBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String value = UpdateProcess.scrub(body.strip());
        return value.length() > MAX_BODY_EXCERPT_CHARS
                ? value.substring(0, MAX_BODY_EXCERPT_CHARS) + "…" : value;
    }
}
