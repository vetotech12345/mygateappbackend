package com.mygateapp.security;

import com.mygateapp.exception.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into an authenticated principal. On every request it confirms that the user is still active
 * and the login session has not been revoked, and loads the user's current roles as {@code ROLE_*} authorities for
 * route-level checks. Society/flat scope is enforced separately in services via {@link AccessService}.
 */
@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String STATE_SQL = """
            SELECT u.status, s.revoked_at, s.expires_at
            FROM users u JOIN refresh_sessions s ON s.user_id = u.id
            WHERE u.id = ? AND s.id = ?
            """;
    private static final String ROLES_SQL = """
            SELECT role_code FROM user_roles WHERE user_id = ?
            UNION
            SELECT m.role_code FROM society_memberships m JOIN societies so ON so.id = m.society_id
            WHERE m.user_id = ? AND m.status = 'ACTIVE' AND so.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JwtAuthenticationConverter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getClaimAsString(JwtConfig.SESSION_CLAIM));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new TokenRejectedException(ErrorCode.INVALID_TOKEN);
        }

        List<SessionState> states = jdbc.query(STATE_SQL,
                (rs, i) -> new SessionState(rs.getString(1), rs.getTimestamp(2), rs.getTimestamp(3)), userId, sessionId);
        if (states.isEmpty()) {
            throw new TokenRejectedException(ErrorCode.SESSION_REVOKED);
        }
        SessionState state = states.getFirst();
        if (!"ACTIVE".equals(state.userStatus())) {
            throw new TokenRejectedException(ErrorCode.USER_INACTIVE);
        }
        if (state.revokedAt() != null || state.expiresAt().toInstant().isBefore(clock.instant())) {
            throw new TokenRejectedException(ErrorCode.SESSION_REVOKED);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        jdbc.query(ROLES_SQL, rs -> {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + rs.getString(1)));
        }, userId, userId);
        return new UserAuthenticationToken(new AuthenticatedUser(userId, sessionId), authorities);
    }

    private record SessionState(String userStatus, Timestamp revokedAt, Timestamp expiresAt) {
    }
}
