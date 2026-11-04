package com.mygateapp.security;

import java.security.Principal;
import java.util.UUID;

/** Principal of an authenticated API request: the user and the login session the access token belongs to. */
public record AuthenticatedUser(UUID userId, UUID sessionId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
