package com.mygateapp.security;

import com.mygateapp.exception.ErrorCode;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** A structurally valid access token that must not be accepted (revoked session, inactive user). */
public class TokenRejectedException extends InvalidBearerTokenException {

    private final ErrorCode code;

    public TokenRejectedException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
