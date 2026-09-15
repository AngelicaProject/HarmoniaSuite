package com.harmoniasuite.source.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harmoniasuite.source.api.dto.CreateSourceUploadRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SourceApiValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void validFullIdentityRequestPasses() {
        assertTrue(validator.validate(request(1, "full", "identity", 4, 4)).isEmpty());
    }

    @Test
    void staticUploadShapeRulesRejectInvalidVersionScopeTransportAndIdentitySize() {
        assertFalse(validator.validate(request(2, "full", "identity", 4, 4)).isEmpty());
        assertFalse(validator.validate(request(1, "partial", "identity", 4, 4)).isEmpty());
        assertFalse(validator.validate(request(1, "full", "gzip", 4, 4)).isEmpty());
        assertFalse(validator.validate(request(1, "full", "identity", 4, 5)).isEmpty());
    }

    private static CreateSourceUploadRequest request(int hxsVersion, String scope,
                                                     String transportEncoding, long uploadSize,
                                                     long uncompressedSize) {
        return new CreateSourceUploadRequest(hxsVersion, "7.2", "en", scope,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "extractor", "lumina", 0L, 0L, 0L, transportEncoding, uploadSize,
                uncompressedSize);
    }
}
