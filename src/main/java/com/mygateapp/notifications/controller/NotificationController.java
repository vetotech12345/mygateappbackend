package com.mygateapp.notifications.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.notifications.dto.DeviceDtos;
import com.mygateapp.notifications.dto.NotificationResponse;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.DeviceService;
import com.mygateapp.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notifications", description = "In-app notifications, push devices and notification preferences")
public class NotificationController {

    private final NotificationService notificationService;
    private final DeviceService deviceService;

    public NotificationController(NotificationService notificationService, DeviceService deviceService) {
        this.notificationService = notificationService;
        this.deviceService = deviceService;
    }

    @GetMapping("/notifications")
    @Operation(summary = "The caller's notifications, newest first")
    public PageResponse<NotificationResponse> list(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return notificationService.list(page, size);
    }

    @GetMapping("/notifications/unread-count")
    public DeviceDtos.UnreadCountResponse unreadCount() {
        return new DeviceDtos.UnreadCountResponse(notificationService.unreadCount());
    }

    @RequestMapping(path = "/notifications/{id}/read", method = {RequestMethod.POST, RequestMethod.PATCH})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one notification as read (POST for the Flutter app, PATCH per the API spec)")
    public void markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notificationService.markAllRead();
    }

    @GetMapping("/users/me/notification-preferences")
    @Operation(summary = "Per-type push preferences. VISITOR_APPROVAL_REQUEST is always enabled.")
    public Map<NotificationType, Boolean> preferences() {
        return notificationService.preferences();
    }

    @PutMapping("/users/me/notification-preferences")
    public Map<NotificationType, Boolean> updatePreferences(@RequestBody Map<NotificationType, Boolean> preferences) {
        return notificationService.updatePreferences(preferences);
    }

    @PostMapping("/devices")
    @Operation(summary = "Register this device for push notifications")
    public DeviceDtos.DeviceResponse registerDevice(@Valid @RequestBody DeviceDtos.RegisterDeviceRequest request) {
        return deviceService.register(request);
    }

    @DeleteMapping("/devices/{idOrToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unregister a device by id or by token")
    public void unregisterDevice(@PathVariable String idOrToken) {
        deviceService.unregister(idOrToken);
    }
}
