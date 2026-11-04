package com.mygateapp.auth.service;

import com.mygateapp.auth.entity.RefreshSession;
import com.mygateapp.auth.repository.RefreshSessionRepository;
import com.mygateapp.common.Hashing;
import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.security.AccessTokenService;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login sessions and refresh-token rotation. Each refresh replaces the token; presenting an already-replaced token
 * is treated as theft and revokes the whole session.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    public record IssuedTokens(UUID userId, UUID sessionId, String accessToken, String refreshToken,
                               long expiresIn, long refreshExpiresIn) {
    }

    private final RefreshSessionRepository sessions;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final AppProperties.Jwt config;
    private final Clock clock;

    public RefreshTokenService(RefreshSessionRepository sessions, UserRepository users, AccessTokenService accessTokens,
                               AppProperties properties, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.accessTokens = accessTokens;
        this.config = properties.jwt();
        this.clock = clock;
    }

    @Transactional
    public IssuedTokens createSession(UUID userId, String ipAddress, String userAgent) {
        Instant now = clock.instant();
        String refreshToken = Hashing.randomToken(32);
        RefreshSession session = new RefreshSession();
        session.setUserId(userId);
        session.setTokenHash(Hashing.sha256Hex(refreshToken));
        session.setIpAddress(ipAddress);
        session.setUserAgent(truncate(userAgent));
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(now.plus(config.refreshTokenTtl()));
        sessions.save(session);
        return tokens(session, refreshToken);
    }

    /** Rotates the refresh token. Reuse detection must be persisted even though an error is returned. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens rotate(String refreshToken, String ipAddress, String userAgent) {
        String hash = Hashing.sha256Hex(refreshToken.trim());
        Instant now = clock.instant();
        Optional<RefreshSession> current = sessions.findByTokenHashForUpdate(hash);
        if (current.isEmpty()) {
            sessions.findByPreviousTokenHash(hash).ifPresent(stolen -> {
                if (stolen.getRevokedAt() == null) {
                    stolen.setRevokedAt(now);
                    stolen.setRevokedReason("REFRESH_TOKEN_REUSED");
                    log.warn("Refresh token reuse detected; session {} revoked", stolen.getId());
                }
                throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSED);
            });
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        RefreshSession session = current.get();
        if (!session.isUsable(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        User user = users.findById(session.getUserId()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (!user.isActive()) {
            session.setRevokedAt(now);
            session.setRevokedReason("USER_INACTIVE");
            throw new ApiException(ErrorCode.USER_INACTIVE);
        }
        String newToken = Hashing.randomToken(32);
        session.setPreviousTokenHash(session.getTokenHash());
        session.setTokenHash(Hashing.sha256Hex(newToken));
        session.setRotatedAt(now);
        session.setLastUsedAt(now);
        if (ipAddress != null) {
            session.setIpAddress(ipAddress);
        }
        if (userAgent != null) {
            session.setUserAgent(truncate(userAgent));
        }
        return tokens(session, newToken);
    }

    /** Logout: revokes the session of this refresh token. Unknown tokens are ignored (idempotent). */
    @Transactional
    public void revoke(String refreshToken) {
        sessions.findByTokenHashForUpdate(Hashing.sha256Hex(refreshToken.trim())).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(clock.instant());
                session.setRevokedReason("LOGOUT");
            }
        });
    }

    @Transactional
    public int revokeAll(UUID userId, String reason) {
        return sessions.revokeAllForUser(userId, clock.instant(), reason);
    }

    private IssuedTokens tokens(RefreshSession session, String refreshToken) {
        String accessToken = accessTokens.issue(session.getUserId(), session.getId());
        long refreshExpiresIn = Math.max(0, session.getExpiresAt().getEpochSecond() - clock.instant().getEpochSecond());
        return new IssuedTokens(session.getUserId(), session.getId(), accessToken, refreshToken,
                accessTokens.accessTokenTtlSeconds(), refreshExpiresIn);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
