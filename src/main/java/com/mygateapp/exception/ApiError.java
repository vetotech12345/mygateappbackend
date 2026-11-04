package com.mygateapp.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Error body for every non-2xx response.
 *
 * <pre>{ "success": false, "code": "VISITOR_ALREADY_EXITED", "message": "...", "errors": [], "timestamp": "...", "path": "..." }</pre>
 */
@Schema(name = "ApiError", description = "Error response")
public record ApiError(
        boolean success,
        String code,
        String message,
        List<FieldViolation> errors,
        Instant timestamp,
        String path) {

    public static ApiError of(ErrorCode code, String message, List<FieldViolation> errors, String path) {
        return new ApiError(false, code.name(), message, errors == null ? List.of() : errors,
                Instant.now().truncatedTo(ChronoUnit.MICROS), path);
    }

    @Schema(name = "FieldViolation")
    public record FieldViolation(String field, String message) {
    }
}
