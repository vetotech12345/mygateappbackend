package com.mygateapp.society.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietySettings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SocietyDtos {

    private SocietyDtos() {
    }

    public record CreateSocietyRequest(
            @Schema(example = "GREENWOOD") @NotBlank(message = "Society code is required")
            @Pattern(regexp = "^[A-Za-z0-9-]{3,20}$", message = "Code must be 3-20 letters, digits or hyphens") String code,
            @Schema(example = "Greenwood Residency") @NotBlank(message = "Name is required") @Size(max = 120) String name,
            @NotBlank(message = "Address is required") @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @NotBlank(message = "City is required") @Size(max = 80) String city,
            @NotBlank(message = "State is required") @Size(max = 80) String state,
            @NotBlank(message = "PIN code is required")
            @Pattern(regexp = "^[A-Za-z0-9 -]{3,10}$", message = "Enter a valid PIN code") String pinCode,
            @Size(max = 60) String country,
            @Size(max = 20) String contactPhone,
            @Email(message = "Enter a valid email address") @Size(max = 254) String contactEmail,
            @Schema(example = "Asia/Kolkata") @Size(max = 40) String timeZone,
            Society.SubscriptionPlan subscriptionPlan,
            LocalDate subscriptionValidUntil) {
    }

    public record UpdateSocietyRequest(
            @NotBlank(message = "Name is required") @Size(max = 120) String name,
            @NotBlank(message = "Address is required") @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @NotBlank(message = "City is required") @Size(max = 80) String city,
            @NotBlank(message = "State is required") @Size(max = 80) String state,
            @NotBlank(message = "PIN code is required")
            @Pattern(regexp = "^[A-Za-z0-9 -]{3,10}$", message = "Enter a valid PIN code") String pinCode,
            @Size(max = 60) String country,
            @Size(max = 20) String contactPhone,
            @Email(message = "Enter a valid email address") @Size(max = 254) String contactEmail,
            @Size(max = 40) String timeZone) {
    }

    public record UpdateSocietyStatusRequest(
            @NotNull(message = "status is required") Society.Status status,
            Society.SubscriptionPlan subscriptionPlan,
            LocalDate subscriptionValidUntil,
            @Size(max = 200) String reason) {
    }

    public record SocietyResponse(
            UUID id, String code, String name, String addressLine1, String addressLine2, String city, String state,
            String pinCode, String country, String contactPhone, String contactEmail, String timeZone,
            Society.Status status, Society.SubscriptionPlan subscriptionPlan, LocalDate subscriptionValidUntil,
            Instant createdAt, Instant updatedAt) {

        public static SocietyResponse of(Society s) {
            return new SocietyResponse(s.getId(), s.getCode(), s.getName(), s.getAddressLine1(), s.getAddressLine2(),
                    s.getCity(), s.getState(), s.getPinCode(), s.getCountry(), s.getContactPhone(), s.getContactEmail(),
                    s.getTimeZone(), s.getStatus(), s.getSubscriptionPlan(), s.getSubscriptionValidUntil(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record SettingsDto(
            @NotNull @Min(1) @Max(720) Integer maxInviteValidityHours,
            @NotNull @Min(30) @Max(3600) Integer approvalTimeoutSeconds,
            @NotNull @Min(5) @Max(1440) Integer pendingVisitorExpiryMinutes,
            @NotNull @Min(30) @Max(3650) Integer visitorRetentionDays,
            @Size(max = 30) List<@NotBlank @Size(max = 60) String> visitPurposes,
            @Size(max = 50) List<@NotBlank @Size(max = 60) String> deliveryCompanies,
            Map<DeliveryCategory, Boolean> deliveryApprovalRules) {

        public static SettingsDto of(SocietySettings s) {
            Map<DeliveryCategory, Boolean> rules = new EnumMap<>(DeliveryCategory.class);
            for (DeliveryCategory category : DeliveryCategory.values()) {
                rules.put(category, s.deliveryRequiresApproval(category));
            }
            return new SettingsDto(s.getMaxInviteValidityHours(), s.getApprovalTimeoutSeconds(),
                    s.getPendingVisitorExpiryMinutes(), s.getVisitorRetentionDays(), s.getVisitPurposes(),
                    s.getDeliveryCompanies(), rules);
        }
    }

    public record AddSocietyAdminRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 60) String name,
            @NotBlank(message = "Mobile number is required") String mobile,
            String countryCode,
            @Email(message = "Enter a valid email address") @Size(max = 254) String email) {
    }

    public record MemberResponse(UUID userId, String name, String mobile, String email, Role role, String status,
                                 Instant since) {
    }

    public record PlatformStatsResponse(long totalSocieties, long activeSocieties, long inactiveSocieties,
                                        long activeResidents, long activeGuards, long activeSocietyAdmins, long totalUsers) {
    }

    public record PlatformSettingDto(String key, String value, String description, Instant updatedAt) {
    }

    /** Values shown in app forms. {@code requiresApproval} is only set for delivery categories. */
    public record LookupItem(String code, String label,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Boolean requiresApproval) {

        public static LookupItem of(String code, String label) {
            return new LookupItem(code, label, null);
        }
    }

    /** {@code GET /config/lookups}: same keys as the Flutter {@code ReferenceData.fromJson}. */
    public record LookupsResponse(
            List<LookupItem> visitorTypes,
            List<LookupItem> deliveryCategories,
            List<LookupItem> staffCategories,
            List<LookupItem> complaintCategories,
            List<LookupItem> noticeCategories,
            List<String> visitPurposes,
            List<String> deliveryCompanies,
            int maxInviteValidityHours,
            int approvalTimeoutSeconds) {
    }
}
