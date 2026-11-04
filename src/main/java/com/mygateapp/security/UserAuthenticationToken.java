package com.mygateapp.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Authentication stored in the security context. The raw JWT is deliberately not kept as credentials. */
public class UserAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    public UserAuthenticationToken(AuthenticatedUser principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }
}
