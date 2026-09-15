package com.harmoniasuite.service.source;

import com.harmoniasuite.dto.SourceSheetDto;
import com.harmoniasuite.dto.SourceSnapshotDto;
import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteCanonicalDatabaseException;
import com.harmoniasuite.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.source.store.SourceSnapshot;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Read and preflight operations for the canonical trusted source registry. */
@Service
public class SourceSnapshotService {

    private static final Pattern SHA256_ID = Pattern.compile("sha256:[0-9a-f]{64}");

    private final SourceSnapshotStore store;

    public SourceSnapshotService(SourceSnapshotStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public SourceSnapshotPreflightResponse preflight(SourceSnapshotPreflightRequest request) {
        validate(request);
        Optional<SourceSnapshot> stored = read(() -> store.findBySnapshotId(request.snapshotId()));
        if (stored.isEmpty()) {
            return SourceSnapshotPreflightResponse.uploadRequired(request.snapshotId());
        }

        SourceSnapshot snapshot = stored.get();
        if (!metadataMatches(snapshot, request)) {
            throw new HarmoniaSuiteConflictException(
                    "snapshot metadata conflicts with the trusted source snapshot");
        }
        return SourceSnapshotPreflightResponse.available(SourceSnapshotDto.from(snapshot));
    }

    public List<SourceSnapshotDto> listSnapshots() {
        return read(() -> store.listSnapshots()).stream()
                .map(SourceSnapshotDto::from)
                .toList();
    }

    public SourceSnapshotDto getSnapshot(String snapshotId) {
        SourceSnapshot snapshot = findSnapshot(snapshotId);
        return SourceSnapshotDto.from(snapshot);
    }

    public List<SourceSheetDto> listSheets(String snapshotId) {
        findSnapshot(snapshotId);
        return read(() -> store.findSheets(snapshotId)).stream()
                .map(SourceSheetDto::from)
                .toList();
    }

    private SourceSnapshot findSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new HarmoniaSuiteBadRequestException("snapshotId must not be blank");
        }
        return read(() -> store.findBySnapshotId(snapshotId))
                .orElseThrow(() -> new HarmoniaSuiteNotFoundException(
                        "source snapshot was not found"));
    }

    private static void validate(SourceSnapshotPreflightRequest request) {
        if (request == null) {
            throw new HarmoniaSuiteBadRequestException("preflight request must not be null");
        }
        if (request.hxsVersion() == null || request.hxsVersion() != 1) {
            throw new HarmoniaSuiteBadRequestException("hxsVersion must be 1");
        }
        if (isBlank(request.gameVersion()) || isBlank(request.language())
                || isBlank(request.scope()) || isBlank(request.extractorVersion())
                || isBlank(request.luminaVersion())) {
            throw new HarmoniaSuiteBadRequestException("source snapshot metadata is incomplete");
        }
        if (!"full".equals(request.scope())) {
            throw new HarmoniaSuiteBadRequestException("scope must be full");
        }
        if (!isValidId(request.snapshotId()) || !isValidId(request.contentId())) {
            throw new HarmoniaSuiteBadRequestException(
                    "snapshotId and contentId must be sha256 IDs with lowercase hexadecimal values");
        }
        if (request.sheetCount() == null || request.sheetCount() < 0
                || request.rowCount() == null || request.rowCount() < 0
                || request.stringCellCount() == null || request.stringCellCount() < 0) {
            throw new HarmoniaSuiteBadRequestException("source snapshot counts must be non-negative");
        }
    }

    private static boolean metadataMatches(SourceSnapshot stored,
                                           SourceSnapshotPreflightRequest request) {
        return stored.hxsVersion() == request.hxsVersion()
                && stored.snapshotId().equals(request.snapshotId())
                && stored.contentId().equals(request.contentId())
                && stored.gameVersion().equals(request.gameVersion())
                && stored.language().equals(request.language())
                && stored.scope().equals(request.scope())
                && stored.extractorVersion().equals(request.extractorVersion())
                && stored.luminaVersion().equals(request.luminaVersion())
                && stored.sheetCount() == request.sheetCount()
                && stored.rowCount() == request.rowCount()
                && stored.stringCellCount() == request.stringCellCount();
    }

    private static boolean isValidId(String value) {
        return value != null && SHA256_ID.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> T read(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw new HarmoniaSuiteCanonicalDatabaseException(exception);
        }
    }
}
