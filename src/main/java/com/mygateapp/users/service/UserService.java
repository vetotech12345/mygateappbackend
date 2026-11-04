package com.mygateapp.users.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.auth.service.RefreshTokenService;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Strings;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.repository.DeviceRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.users.dto.UserDtos;
import com.mygateapp.users.dto.UserMeResponse;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.enums.UserStatus;
import com.mygateapp.users.repository.UserRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final UserMeService userMeService;
    private final RefreshTokenService refreshTokens;
    private final DeviceRepository devices;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public UserService(UserRepository users, UserMeService userMeService, RefreshTokenService refreshTokens,
                       DeviceRepository devices, AccessService access, AuditService audit, Clock clock) {
        this.users = users;
        this.userMeService = userMeService;
        this.refreshTokens = refreshTokens;
        this.devices = devices;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UserMeResponse updateProfile(UserDtos.UpdateProfileRequest request) {
        User user = users.findById(access.currentUserId()).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        user.setName(request.name().trim().replaceAll("\\s+", " "));
        user.setEmail(Strings.trimToNull(request.email()) == null ? null : request.email().trim().toLowerCase());
        users.flush();
        return userMeService.build(user.getId());
    }

    /**
     * Platform-level account activation / deactivation. Deactivation revokes every session and push device at once;
     * the next request with an old access token is rejected by the authentication converter.
     */
    @Transactional
    public UserMeResponse updateStatus(UUID userId, UserDtos.UpdateUserStatusRequest request) {
        access.requirePlatform(Permission.PLATFORM_USER_STATUS);
        if (userId.equals(access.currentUserId())) {
            throw ApiException.field("status", "You cannot change the status of your own account");
        }
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        UserStatus previous = user.getStatus();
        user.setStatus(request.status());
        if (request.status() == UserStatus.INACTIVE) {
            deactivateSessions(user.getId(), "USER_DEACTIVATED");
        }
        audit.record(null, "USER_STATUS_CHANGED", "USER", user.getId(),
                "User " + MobileNumbers.mask(user.getMobile()) + " " + previous + " -> " + request.status(),
                Map.of("from", previous.name(), "to", request.status().name(),
                        "reason", request.reason() == null ? "" : request.reason()));
        return userMeService.build(user.getId());
    }

    /** Ends all login sessions and push registrations of a user (deactivation, removal from the last society). */
    @Transactional
    public void deactivateSessions(UUID userId, String reason) {
        refreshTokens.revokeAll(userId, reason);
        devices.deactivateAllForUser(userId, reason, clock.instant());
    }

    /**
     * Finds the user by mobile or creates a new ACTIVE user without a name (they complete their profile at first
     * login). Used by admins when registering residents, guards and society administrators.
     */
    @Transactional
    public User findOrCreate(String countryCode, String mobile, String name, String email) {
        String cc = MobileNumbers.normalizeCountryCode(countryCode);
        String number = MobileNumbers.normalize(cc, mobile, "mobile");
        return users.findByCountryCodeAndMobile(cc, number).map(existing -> {
            if (!existing.isProfileComplete() && Strings.trimToNull(name) != null) {
                existing.setName(name.trim());
            }
            if (existing.getEmail() == null && Strings.trimToNull(email) != null) {
                existing.setEmail(email.trim().toLowerCase());
            }
            return existing;
        }).orElseGet(() -> {
            User user = new User();
            user.setCountryCode(cc);
            user.setMobile(number);
            user.setName(Strings.trimToNull(name));
            user.setEmail(Strings.trimToNull(email) == null ? null : email.trim().toLowerCase());
            user.setStatus(UserStatus.ACTIVE);
            return users.save(user);
        });
    }
}
