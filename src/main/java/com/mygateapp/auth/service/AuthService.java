package com.mygateapp.auth.service;

import com.mygateapp.auth.dto.AuthDtos;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import com.mygateapp.users.service.UserMeService;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Mobile + OTP authentication. Only existing, active users with an active or pending society membership (or a
 * platform role) can sign in: OTP verification never creates accounts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final SocietyMembershipRepository memberships;
    private final JdbcTemplate jdbc;
    private final OtpService otpService;
    private final RefreshTokenService refreshTokens;
    private final UserMeService userMeService;
    private final Clock clock;

    public AuthService(UserRepository users, SocietyMembershipRepository memberships, JdbcTemplate jdbc,
                       OtpService otpService, RefreshTokenService refreshTokens, UserMeService userMeService, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.otpService = otpService;
        this.refreshTokens = refreshTokens;
        this.userMeService = userMeService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuthDtos.OtpChallengeResponse requestOtp(AuthDtos.RequestOtpRequest request, String clientIp) {
        String countryCode = MobileNumbers.normalizeCountryCode(request.countryCode());
        String mobile = MobileNumbers.normalize(countryCode, request.mobile(), "mobile");
        otpService.checkRequestLimits(countryCode, mobile, clientIp);
        User user = requireLoginAllowed(countryCode, mobile);
        OtpService.Challenge challenge = otpService.issue(countryCode, mobile);
        log.info("OTP requested for user {}", user.getId());
        return new AuthDtos.OtpChallengeResponse(challenge.requestId(), challenge.resendAfterSeconds(), challenge.expiresInSeconds());
    }

    @Transactional
    public AuthDtos.TokenResponse verifyOtp(AuthDtos.VerifyOtpRequest request, String clientIp, String userAgent) {
        String countryCode = MobileNumbers.normalizeCountryCode(request.countryCode());
        String mobile = MobileNumbers.normalize(countryCode, request.mobile(), "mobile");
        otpService.verify(countryCode, mobile, request.otp(), request.requestId(), clientIp);
        User user = requireLoginAllowed(countryCode, mobile);
        user.setLastLoginAt(clock.instant());
        RefreshTokenService.IssuedTokens tokens = refreshTokens.createSession(user.getId(), clientIp, userAgent);
        log.info("User {} signed in (session {})", user.getId(), tokens.sessionId());
        return response(tokens);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request, String clientIp, String userAgent) {
        return response(refreshTokens.rotate(request.refreshToken(), clientIp, userAgent));
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request) {
        if (request != null && StringUtils.hasText(request.refreshToken())) {
            refreshTokens.revoke(request.refreshToken());
        }
    }

    private AuthDtos.TokenResponse response(RefreshTokenService.IssuedTokens tokens) {
        return new AuthDtos.TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.expiresIn(),
                tokens.refreshExpiresIn(), userMeService.build(tokens.userId()));
    }

    private User requireLoginAllowed(String countryCode, String mobile) {
        User user = users.findByCountryCodeAndMobile(countryCode, mobile)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_REGISTERED));
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.USER_INACTIVE);
        }
        boolean platformRole = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles WHERE user_id = ?)", Boolean.class, user.getId()));
        boolean member = memberships.existsByUserIdAndStatusIn(user.getId(),
                List.of(SocietyMembership.Status.ACTIVE, SocietyMembership.Status.PENDING));
        if (!platformRole && !member) {
            throw new ApiException(ErrorCode.NO_ACTIVE_MEMBERSHIP);
        }
        return user;
    }
}
