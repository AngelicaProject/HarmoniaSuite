package com.harmoniasuite.source.api.validation;

import com.harmoniasuite.source.api.dto.CreateSourceUploadRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Validates transport-dependent shape at the HTTP boundary. */
@Documented
@Target({TYPE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidSourceUploadRequest.Validator.class)
public @interface ValidSourceUploadRequest {

    String message() default "upload declaration is invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidSourceUploadRequest,
            CreateSourceUploadRequest> {

        @Override
        public boolean isValid(CreateSourceUploadRequest request, ConstraintValidatorContext context) {
            if (request == null || !"identity".equals(request.transportEncoding())
                    || request.uploadSize() == null || request.uncompressedSize() == null) {
                return true;
            }
            if (request.uploadSize().equals(request.uncompressedSize())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            "identity upload_size must equal uncompressed_size")
                    .addPropertyNode("uploadSize")
                    .addConstraintViolation();
            return false;
        }
    }
}
