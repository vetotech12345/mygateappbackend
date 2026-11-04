package com.mygateapp.notifications.dto;

import com.mygateapp.notifications.entity.UserDevice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record RegisterDeviceRequest(
            @NotBlank(message = "Device token is required") @Size(max = 512, message = "Device token is too long") String token,
            @NotNull(message = "Platform is required") UserDevice.Platform platform) {
    }

    /** The token itself is never echoed back. */
    public record DeviceResponse(UUID id, UserDevice.Platform platform, boolean active, Instant createdAt, Instant lastSeenAt) {

        public static DeviceResponse of(UserDevice device) {
            return new DeviceResponse(device.getId(), device.getPlatform(), device.isActive(), device.getCreatedAt(),
                    device.getLastSeenAt());
        }
    }

    public record UnreadCountResponse(long count) {
    }
}
