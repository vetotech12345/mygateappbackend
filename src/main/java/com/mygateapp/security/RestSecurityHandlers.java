package com.mygateapp.security;

import com.mygateapp.common.web.JsonErrorWriter;
import com.mygateapp.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** JSON 401/403 responses for the security filter chain, in the same {@code ApiError} format as controllers. */
@Component
public class RestSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonErrorWriter errorWriter;

    public RestSecurityHandlers(JsonErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ErrorCode code = switch (ex) {
            case TokenRejectedException rejected -> rejected.code() == ErrorCode.USER_INACTIVE
                    ? ErrorCode.SESSION_REVOKED : rejected.code();
            case InvalidBearerTokenException ignored -> ErrorCode.INVALID_TOKEN;
            default -> ErrorCode.UNAUTHORIZED;
        };
        String message = ex instanceof TokenRejectedException rejected && rejected.code() == ErrorCode.USER_INACTIVE
                ? ErrorCode.USER_INACTIVE.defaultMessage() : code.defaultMessage();
        response.setHeader("WWW-Authenticate", "Bearer");
        errorWriter.write(request, response, code, message);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        errorWriter.write(request, response, ErrorCode.ACCESS_DENIED, null);
    }
}
