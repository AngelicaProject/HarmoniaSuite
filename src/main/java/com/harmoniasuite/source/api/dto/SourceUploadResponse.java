package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Passive lifecycle response; it intentionally contains no managed paths or metadata claims. */
public record SourceUploadResponse(
        @JsonProperty("upload_id") String uploadId,
        String state,
        @JsonProperty("transport_encoding") String transportEncoding,
        @JsonProperty("upload_size") Long uploadSize,
        @JsonProperty("uncompressed_size") Long uncompressedSize,
        @JsonProperty("received_bytes") Long receivedBytes,
        @JsonProperty("next_offset") Long nextOffset,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage) {
}
