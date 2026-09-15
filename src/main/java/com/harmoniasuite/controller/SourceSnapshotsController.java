package com.harmoniasuite.controller;

import com.harmoniasuite.dto.SourceSheetDto;
import com.harmoniasuite.dto.SourceSnapshotDto;
import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.dto.SourceSnapshotUploadRequest;
import com.harmoniasuite.dto.SourceUploadResponse;
import com.harmoniasuite.service.source.SourceSnapshotService;
import com.harmoniasuite.service.source.SourceUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for canonical source snapshots, separate from the legacy source workflow. */
@RestController
@RequestMapping("/api/source-snapshots")
public class SourceSnapshotsController {

    private final SourceSnapshotService snapshots;
    private final SourceUploadService uploads;

    public SourceSnapshotsController(SourceSnapshotService snapshots) {
        this(snapshots, null);
    }

    @Autowired
    public SourceSnapshotsController(SourceSnapshotService snapshots, SourceUploadService uploads) {
        this.snapshots = snapshots;
        this.uploads = uploads;
    }

    @PostMapping("/preflight")
    public SourceSnapshotPreflightResponse preflight(
            @Valid @RequestBody SourceSnapshotPreflightRequest request) {
        return snapshots.preflight(request);
    }

    @PostMapping("/uploads")
    public ResponseEntity<SourceUploadResponse> createUpload(
            @Valid @RequestBody SourceSnapshotUploadRequest request) {
        requireUploads();
        SourceUploadResponse response = uploads.create(request);
        return "AVAILABLE".equals(response.status())
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    @PutMapping(value = "/uploads/{uploadId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public SourceUploadResponse appendUpload(@PathVariable String uploadId,
                                             @RequestHeader(value = "Upload-Offset", required = false)
                                             Long offset,
                                             @RequestHeader(value = "Content-Length", required = false)
                                             Long contentLength,
                                             HttpServletRequest request) throws IOException {
        requireUploads();
        return uploads.append(uploadId, offset, contentLength, request.getInputStream());
    }

    @GetMapping("/uploads/{uploadId}")
    public SourceUploadResponse uploadStatus(@PathVariable String uploadId) {
        requireUploads();
        return uploads.status(uploadId);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<SourceUploadResponse> completeUpload(@PathVariable String uploadId) {
        requireUploads();
        return ResponseEntity.accepted().body(uploads.complete(uploadId));
    }

    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> cancelUpload(@PathVariable String uploadId) {
        requireUploads();
        uploads.cancel(uploadId);
        return ResponseEntity.noContent().build();
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

    private void requireUploads() {
        if (uploads == null) {
            throw new IllegalStateException("source upload service is unavailable");
        }
    }
}
