package com.harmoniasuite.source.api;

import com.harmoniasuite.source.api.dto.SourceSheetResponse;
import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.source.api.dto.SourceSnapshotResponse;
import com.harmoniasuite.source.application.SourceSnapshotService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST adapter for canonical trusted snapshot discovery. */
@RestController
@RequestMapping("/api/source-snapshots")
public final class SourceSnapshotController {

    private final SourceSnapshotService snapshotService;
    private final SourceApiMapper mapper;

    public SourceSnapshotController(SourceSnapshotService snapshots, SourceApiMapper mapper) {
        this.snapshotService = Objects.requireNonNull(snapshots, "snapshots");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostMapping("/preflight")
    public SourceSnapshotPreflightResponse preflight(
            @Valid @RequestBody SourceSnapshotPreflightRequest request) {
        return mapper.toPreflightResponse(snapshotService.preflight(mapper.toMetadata(request)));
    }

    @GetMapping
    public List<SourceSnapshotResponse> listSnapshots() {
        return snapshotService.listSnapshots().stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{snapshotId}")
    public SourceSnapshotResponse getSnapshot(@PathVariable String snapshotId) {
        return mapper.toResponse(snapshotService.getSnapshot(snapshotId));
    }

    @GetMapping("/{snapshotId}/sheets")
    public List<SourceSheetResponse> listSheets(@PathVariable String snapshotId) {
        return snapshotService.listSheets(snapshotId).stream().map(mapper::toResponse).toList();
    }
}
