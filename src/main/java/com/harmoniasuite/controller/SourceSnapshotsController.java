package com.harmoniasuite.controller;

import com.harmoniasuite.dto.SourceSheetDto;
import com.harmoniasuite.dto.SourceSnapshotDto;
import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.service.source.SourceSnapshotService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for canonical source snapshots, separate from the legacy source workflow. */
@RestController
@RequestMapping("/api/source-snapshots")
public class SourceSnapshotsController {

    private final SourceSnapshotService snapshots;

    public SourceSnapshotsController(SourceSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @PostMapping("/preflight")
    public SourceSnapshotPreflightResponse preflight(
            @Valid @RequestBody SourceSnapshotPreflightRequest request) {
        return snapshots.preflight(request);
    }

    @GetMapping
    public List<SourceSnapshotDto> listSnapshots() {
        return snapshots.listSnapshots();
    }

    @GetMapping("/{snapshotId}")
    public SourceSnapshotDto getSnapshot(@PathVariable String snapshotId) {
        return snapshots.getSnapshot(snapshotId);
    }

    @GetMapping("/{snapshotId}/sheets")
    public List<SourceSheetDto> listSheets(@PathVariable String snapshotId) {
        return snapshots.listSheets(snapshotId);
    }
}
