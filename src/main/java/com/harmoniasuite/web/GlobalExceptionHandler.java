package com.harmoniasuite.web;

import com.harmoniasuite.source.api.dto.ApiErrorResponse;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.source.application.exception.SourceUploadTooLargeException;
import com.harmoniasuite.source.application.exception.UploadOffsetConflictException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ApiErrorResponse> handleConversion(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request could not be parsed");
    }

    @ExceptionHandler(HarmoniaSuiteBadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(HarmoniaSuiteBadRequestException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(HarmoniaSuiteNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMissing(HarmoniaSuiteNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(HarmoniaSuiteConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(HarmoniaSuiteConflictException exception) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(UploadOffsetConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadOffset(UploadOffsetConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse("OFFSET_CONFLICT",
                exception.getMessage(), List.of(), Map.of("expected_offset", exception.expectedOffset())));
    }

    @ExceptionHandler(SourceUploadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(SourceUploadTooLargeException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabase(DataAccessException ignored) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_UNAVAILABLE", "database is unavailable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiErrorResponse.Violation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.Violation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_FAILED",
                "request validation failed", violations, Map.of()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintValidation(
            ConstraintViolationException exception) {
        List<ApiErrorResponse.Violation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiErrorResponse.Violation(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_FAILED",
                "request validation failed", violations, Map.of()));
    }

    @ExceptionHandler({NoSuchFileException.class, FileNotFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(IOException ignored) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "resource was not found");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> handleIo(IOException ignored) {
        return response(HttpStatus.BAD_REQUEST, "IO_ERROR", "request could not be processed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ignored) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error");
    }

    private static ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, List.of(), Map.of()));
    }
}
