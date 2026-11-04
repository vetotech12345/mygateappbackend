package com.mygateapp.exception;

import java.util.List;

/** Business failure with a stable {@link ErrorCode}. The message must be safe to show to end users. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<ApiError.FieldViolation> fieldErrors;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ApiError.FieldViolation> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiException field(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, List.of(new ApiError.FieldViolation(field, message)));
    }

    public static ApiException field(ErrorCode code, String field, String message) {
        return new ApiException(code, message, List.of(new ApiError.FieldViolation(field, message)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.FieldViolation> fieldErrors() {
        return fieldErrors;
    }
}
