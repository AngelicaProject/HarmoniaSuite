package com.harmoniasuite.source.api;

import com.harmoniasuite.source.api.dto.CreateSourceUploadRequest;
import com.harmoniasuite.source.api.dto.CreateSourceUploadResponse;
import com.harmoniasuite.source.api.dto.SourceUploadResponse;
import com.harmoniasuite.source.application.SourceUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** REST adapter for resumable source upload lifecycle operations. */
@RestController
@Validated
@RequestMapping("/api/source-snapshots/uploads")
public class SourceUploadController {

    private final SourceUploadService uploadService;
    private final SourceApiMapper mapper;

    public SourceUploadController(SourceUploadService uploads, SourceApiMapper mapper) {
        this.uploadService = Objects.requireNonNull(uploads, "uploads");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostMapping
    public ResponseEntity<CreateSourceUploadResponse> create(
            @Valid @RequestBody CreateSourceUploadRequest request) {
        var result = uploadService.create(mapper.toCommand(request));
        var response = mapper.toCreateResponse(result);
        return result.status().name().equals("AVAILABLE")
                ? ResponseEntity.ok(response) : ResponseEntity.status(201).body(response);
    }

    @PutMapping(value = "/{uploadId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public SourceUploadResponse append(@PathVariable java.util.UUID uploadId,
                                       @RequestHeader("Upload-Offset") @Min(0) Long offset,
                                       @RequestHeader("Content-Length") @Positive Long contentLength,
                                       HttpServletRequest request) throws IOException {
        return mapper.toResponse(uploadService.append(uploadId, offset, contentLength,
                request.getInputStream()));
    }

    @GetMapping("/{uploadId}")
    public SourceUploadResponse status(@PathVariable java.util.UUID uploadId) {
        return mapper.toResponse(uploadService.status(uploadId));
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<SourceUploadResponse> complete(@PathVariable java.util.UUID uploadId) {
        return ResponseEntity.accepted().body(mapper.toResponse(uploadService.complete(uploadId)));
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> cancel(@PathVariable java.util.UUID uploadId) {
        uploadService.cancel(uploadId);
        return ResponseEntity.noContent().build();
    }
}
