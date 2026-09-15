package com.harmoniasuite.source.api;

import com.harmoniasuite.source.api.dto.CreateSourceUploadRequest;
import com.harmoniasuite.source.api.dto.CreateSourceUploadResponse;
import com.harmoniasuite.source.api.dto.SourceSheetResponse;
import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.source.api.dto.SourceSnapshotResponse;
import com.harmoniasuite.source.api.dto.SourceUploadResponse;
import com.harmoniasuite.source.application.command.CreateSourceUploadCommand;
import com.harmoniasuite.source.application.model.SourceSnapshotAvailabilityResult;
import com.harmoniasuite.source.application.model.SourceUploadCreationResult;
import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.domain.SourceSheet;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.Sha256Id;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SourceApiMapper {

    default String toHex(Sha256Digest digest) {
        return digest == null ? null : digest.hex();
    }

    default Sha256Id toSha256Id(String value) {
        return value == null ? null : Sha256Id.parse(value);
    }

    SourceSnapshotMetadata toMetadata(SourceSnapshotPreflightRequest request);

    SourceSnapshotMetadata toMetadata(CreateSourceUploadRequest request);

    default CreateSourceUploadCommand toCommand(CreateSourceUploadRequest request) {
        return new CreateSourceUploadCommand(toMetadata(request), request.transportEncoding(),
                request.uploadSize(), request.uncompressedSize());
    }

    SourceSnapshotResponse toResponse(SourceSnapshot snapshot);

    SourceSheetResponse toResponse(SourceSheet sheet);

    @Mapping(target = "uploadId", expression = "java(session.uploadId().toString())")
    @Mapping(target = "state", expression = "java(session.state().name())")
    @Mapping(target = "transportEncoding", expression = "java(session.transportEncoding().wireValue())")
    @Mapping(target = "nextOffset", source = "receivedBytes")
    @Mapping(target = "errorCode", expression = "java(session.errorCode() == null ? null : session.errorCode().name())")
    SourceUploadResponse toResponse(SourceUploadSession session);

    default SourceSnapshotPreflightResponse toPreflightResponse(SourceSnapshotAvailabilityResult result) {
        return new SourceSnapshotPreflightResponse(result.status().name(),
                result.status().name().equals("UPLOAD_REQUIRED") ? result.snapshotId() : null,
                result.snapshot() == null ? null : toResponse(result.snapshot()));
    }

    default CreateSourceUploadResponse toCreateResponse(SourceUploadCreationResult result) {
        return new CreateSourceUploadResponse(result.status().name(),
                result.snapshot() == null ? null : toResponse(result.snapshot()),
                result.upload() == null ? null : toResponse(result.upload()));
    }
}
