package com.mygateapp.notifications.service;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.dto.DeviceDtos;
import com.mygateapp.notifications.entity.UserDevice;
import com.mygateapp.notifications.repository.DeviceRepository;
import com.mygateapp.security.AccessService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final AccessService access;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, AccessService access, Clock clock) {
        this.devices = devices;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Registers (or refreshes) a push target for the current user. A token already registered by another user is
     * moved to the current user, because the device changed hands / accounts.
     */
    @Transactional
    public DeviceDtos.DeviceResponse register(DeviceDtos.RegisterDeviceRequest request) {
        UUID userId = access.currentUserId();
        Instant now = clock.instant();
        String token = request.token().trim();
        UserDevice device = devices.findByToken(token).orElseGet(UserDevice::new);
        device.setUserId(userId);
        device.setToken(token);
        device.setPlatform(request.platform());
        device.setActive(true);
        device.setFailureCount(0);
        device.setDeactivatedReason(null);
        device.setLastSeenAt(now);
        return DeviceDtos.DeviceResponse.of(devices.save(device));
    }

    /** Accepts the device id (master prompt) or the token itself (Flutter). Only the caller's devices are affected. */
    @Transactional
    public void unregister(String idOrToken) {
        UUID userId = access.currentUserId();
        UserDevice device = parseUuid(idOrToken)
                .flatMap(id -> devices.findByIdAndUserId(id, userId))
                .or(() -> devices.findByTokenAndUserId(idOrToken, userId))
                .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_NOT_FOUND));
        devices.delete(device);
    }

    private static java.util.Optional<UUID> parseUuid(String value) {
        try {
            return java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }
}
