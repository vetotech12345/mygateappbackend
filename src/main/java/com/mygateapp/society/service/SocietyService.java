package com.mygateapp.society.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.dto.SocietyDtos;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.entity.SocietySettings;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.society.repository.SocietyRepository;
import com.mygateapp.society.repository.SocietySettingsRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import com.mygateapp.users.service.UserService;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Societies, their configuration and their administrators. */
@Service
public class SocietyService {

    private static final Set<String> SORT_FIELDS = Set.of("name", "code", "city", "createdAt", "status");

    private final SocietyRepository societies;
    private final SocietySettingsRepository settingsRepository;
    private final SocietyMembershipRepository memberships;
    private final UserRepository users;
    private final UserService userService;
    private final AccessService access;
    private final AuditService audit;

    public SocietyService(SocietyRepository societies, SocietySettingsRepository settingsRepository,
                          SocietyMembershipRepository memberships, UserRepository users, UserService userService,
                          AccessService access, AuditService audit) {
        this.societies = societies;
        this.settingsRepository = settingsRepository;
        this.memberships = memberships;
        this.users = users;
        this.userService = userService;
        this.access = access;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ societies

    @Transactional
    public SocietyDtos.SocietyResponse create(SocietyDtos.CreateSocietyRequest request) {
        access.requirePlatform(Permission.PLATFORM_SOCIETY_CREATE);
        String code = request.code().trim().toUpperCase();
        if (societies.existsByCode(code)) {
            throw ApiException.field(ErrorCode.SOCIETY_CODE_TAKEN, "code", ErrorCode.SOCIETY_CODE_TAKEN.defaultMessage());
        }
        Society society = new Society();
        society.setCode(code);
        applyDetails(society, request.name(), request.addressLine1(), request.addressLine2(), request.city(),
                request.state(), request.pinCode(), request.country(), request.contactPhone(), request.contactEmail(),
                request.timeZone());
        if (request.subscriptionPlan() != null) {
            society.setSubscriptionPlan(request.subscriptionPlan());
        }
        society.setSubscriptionValidUntil(request.subscriptionValidUntil());
        society.setCreatedBy(access.currentUserId());
        try {
            societies.saveAndFlush(society);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.field(ErrorCode.SOCIETY_CODE_TAKEN, "code", ErrorCode.SOCIETY_CODE_TAKEN.defaultMessage());
        }
        SocietySettings settings = new SocietySettings();
        settings.setSocietyId(society.getId());
        settings.setVisitPurposes(new ArrayList<>(SocietySettings.DEFAULT_VISIT_PURPOSES));
        settings.setDeliveryApprovalRules(new EnumMap<>(SocietySettings.DEFAULT_DELIVERY_RULES));
        settingsRepository.save(settings);
        audit.record(society.getId(), "SOCIETY_CREATED", "SOCIETY", society.getId(),
                "Society " + code + " (" + society.getName() + ") created", Map.of("code", code, "name", society.getName()));
        return SocietyDtos.SocietyResponse.of(society);
    }

    @Transactional(readOnly = true)
    public PageResponse<SocietyDtos.SocietyResponse> list(String q, Society.Status status, Integer page, Integer size,
                                                          String sort) {
        var pageable = Pages.of(page, size, sort, SORT_FIELDS, Sort.by("name"));
        String like = Strings.likePattern(q);
        UserAccess current = access.access();
        Page<Society> result;
        if (current.superAdmin()) {
            result = societies.search(status, like, pageable);
        } else {
            List<UUID> ids = current.memberships().stream()
                    .filter(m -> m.role() == Role.SOCIETY_ADMIN)
                    .map(UserAccess.Membership::societyId)
                    .toList();
            if (ids.isEmpty()) {
                return PageResponse.of(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0);
            }
            result = societies.searchWithin(ids, status, like, pageable);
        }
        return PageResponse.of(result, SocietyDtos.SocietyResponse::of);
    }

    @Transactional(readOnly = true)
    public SocietyDtos.SocietyResponse get(UUID id) {
        Society society = requireVisible(id);
        access.require(id, Permission.SOCIETY_VIEW);
        return SocietyDtos.SocietyResponse.of(society);
    }

    @Transactional
    public SocietyDtos.SocietyResponse update(UUID id, SocietyDtos.UpdateSocietyRequest request) {
        Society society = requireVisible(id);
        access.require(id, Permission.SOCIETY_UPDATE);
        Map<String, Object> before = snapshot(society);
        applyDetails(society, request.name(), request.addressLine1(), request.addressLine2(), request.city(),
                request.state(), request.pinCode(), request.country(), request.contactPhone(), request.contactEmail(),
                request.timeZone());
        audit.record(id, "SOCIETY_UPDATED", "SOCIETY", id, "Society details updated", diff(before, snapshot(society)));
        return SocietyDtos.SocietyResponse.of(society);
    }

    @Transactional
    public SocietyDtos.SocietyResponse updateStatus(UUID id, SocietyDtos.UpdateSocietyStatusRequest request) {
        access.requirePlatform(Permission.PLATFORM_SOCIETY_STATUS);
        Society society = requireSociety(id);
        Society.Status previous = society.getStatus();
        society.setStatus(request.status());
        if (request.subscriptionPlan() != null) {
            society.setSubscriptionPlan(request.subscriptionPlan());
        }
        if (request.subscriptionValidUntil() != null) {
            society.setSubscriptionValidUntil(request.subscriptionValidUntil());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", previous.name());
        details.put("to", request.status().name());
        details.put("plan", society.getSubscriptionPlan().name());
        if (request.reason() != null) {
            details.put("reason", request.reason());
        }
        audit.record(id, "SOCIETY_STATUS_CHANGED", "SOCIETY", id,
                "Society " + society.getCode() + " " + previous + " -> " + request.status(), details);
        return SocietyDtos.SocietyResponse.of(society);
    }

    // ------------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public SocietyDtos.SettingsDto getSettings(UUID id) {
        requireVisible(id);
        access.require(id, Permission.SOCIETY_VIEW);
        return SocietyDtos.SettingsDto.of(settingsFor(id));
    }

    @Transactional
    public SocietyDtos.SettingsDto updateSettings(UUID id, SocietyDtos.SettingsDto request) {
        requireVisible(id);
        access.require(id, Permission.SOCIETY_UPDATE);
        SocietySettings settings = settingsFor(id);
        Map<String, Object> before = Map.of("maxInviteValidityHours", settings.getMaxInviteValidityHours(),
                "pendingVisitorExpiryMinutes", settings.getPendingVisitorExpiryMinutes(),
                "visitorRetentionDays", settings.getVisitorRetentionDays());
        settings.setMaxInviteValidityHours(request.maxInviteValidityHours());
        settings.setApprovalTimeoutSeconds(request.approvalTimeoutSeconds());
        settings.setPendingVisitorExpiryMinutes(request.pendingVisitorExpiryMinutes());
        settings.setVisitorRetentionDays(request.visitorRetentionDays());
        if (request.visitPurposes() != null) {
            settings.setVisitPurposes(cleanList(request.visitPurposes()));
        }
        if (request.deliveryCompanies() != null) {
            settings.setDeliveryCompanies(cleanList(request.deliveryCompanies()));
        }
        if (request.deliveryApprovalRules() != null) {
            Map<DeliveryCategory, Boolean> rules = new EnumMap<>(DeliveryCategory.class);
            request.deliveryApprovalRules().forEach((category, required) -> {
                if (category != null && required != null) {
                    rules.put(category, required);
                }
            });
            settings.getDeliveryApprovalRules().clear();
            settings.getDeliveryApprovalRules().putAll(rules);
        }
        audit.record(id, "SOCIETY_SETTINGS_UPDATED", "SOCIETY", id, "Society settings updated",
                Map.of("before", before, "after", Map.of("maxInviteValidityHours", request.maxInviteValidityHours(),
                        "pendingVisitorExpiryMinutes", request.pendingVisitorExpiryMinutes(),
                        "visitorRetentionDays", request.visitorRetentionDays())));
        return SocietyDtos.SettingsDto.of(settings);
    }

    /** Settings row for a society; created with defaults if missing (e.g. societies imported by SQL). */
    @Transactional
    public SocietySettings settingsFor(UUID societyId) {
        return settingsRepository.findById(societyId).orElseGet(() -> {
            SocietySettings settings = new SocietySettings();
            settings.setSocietyId(societyId);
            settings.setVisitPurposes(new ArrayList<>(SocietySettings.DEFAULT_VISIT_PURPOSES));
            return settingsRepository.save(settings);
        });
    }

    // ------------------------------------------------------------- administrators

    @Transactional(readOnly = true)
    public List<SocietyDtos.MemberResponse> listAdmins(UUID societyId) {
        requireVisible(societyId);
        access.require(societyId, Permission.SOCIETY_VIEW);
        List<SocietyMembership> admins = memberships.findBySocietyIdAndRole(societyId, Role.SOCIETY_ADMIN);
        Map<UUID, User> userById = users.findAllById(admins.stream().map(SocietyMembership::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return admins.stream()
                .filter(m -> userById.containsKey(m.getUserId()))
                .map(m -> {
                    User u = userById.get(m.getUserId());
                    return new SocietyDtos.MemberResponse(u.getId(), u.getName(), u.getMobile(), u.getEmail(),
                            Role.SOCIETY_ADMIN, m.getStatus().name(), m.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public SocietyDtos.MemberResponse addAdmin(UUID societyId, SocietyDtos.AddSocietyAdminRequest request) {
        access.requirePlatform(Permission.PLATFORM_ADMIN_MANAGE);
        Society society = requireSociety(societyId);
        User user = userService.findOrCreate(request.countryCode(), request.mobile(), request.name(), request.email());
        SocietyMembership membership = memberships.findBySocietyIdAndUserIdAndRole(societyId, user.getId(), Role.SOCIETY_ADMIN)
                .orElseGet(() -> {
                    SocietyMembership created = new SocietyMembership();
                    created.setSocietyId(societyId);
                    created.setUserId(user.getId());
                    created.setRole(Role.SOCIETY_ADMIN);
                    return created;
                });
        membership.setStatus(SocietyMembership.Status.ACTIVE);
        memberships.save(membership);
        audit.record(societyId, "SOCIETY_ADMIN_ADDED", "USER", user.getId(),
                user.displayName() + " is now an administrator of " + society.getCode(), Map.of());
        return new SocietyDtos.MemberResponse(user.getId(), user.getName(), user.getMobile(), user.getEmail(),
                Role.SOCIETY_ADMIN, membership.getStatus().name(), membership.getCreatedAt());
    }

    @Transactional
    public void removeAdmin(UUID societyId, UUID userId) {
        access.requirePlatform(Permission.PLATFORM_ADMIN_MANAGE);
        requireSociety(societyId);
        SocietyMembership membership = memberships.findBySocietyIdAndUserIdAndRole(societyId, userId, Role.SOCIETY_ADMIN)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "This user is not an administrator of the society."));
        membership.setStatus(SocietyMembership.Status.INACTIVE);
        audit.record(societyId, "SOCIETY_ADMIN_REMOVED", "USER", userId, "Society administrator removed", Map.of());
    }

    // ---------------------------------------------------------------- helpers

    public Society requireSociety(UUID id) {
        return societies.findById(id).orElseThrow(() -> new ApiException(ErrorCode.SOCIETY_NOT_FOUND));
    }

    /** Loads a society the caller may see (404 otherwise, so ids of other societies cannot be probed). */
    public Society requireVisible(UUID id) {
        access.ensureInScope(id, ErrorCode.SOCIETY_NOT_FOUND);
        return requireSociety(id);
    }

    private static void applyDetails(Society society, String name, String line1, String line2, String city, String state,
                                     String pinCode, String country, String phone, String email, String timeZone) {
        society.setName(name.trim());
        society.setAddressLine1(line1.trim());
        society.setAddressLine2(Strings.trimToNull(line2));
        society.setCity(city.trim());
        society.setState(state.trim());
        society.setPinCode(pinCode.trim());
        society.setCountry(Strings.trimToNull(country) == null ? "India" : country.trim());
        society.setContactPhone(Strings.trimToNull(phone));
        society.setContactEmail(Strings.trimToNull(email) == null ? null : email.trim().toLowerCase());
        if (Strings.trimToNull(timeZone) != null) {
            try {
                society.setTimeZone(ZoneId.of(timeZone.trim()).getId());
            } catch (DateTimeException e) {
                throw ApiException.field("timeZone", "Unknown time zone. Use an IANA id such as Asia/Kolkata");
            }
        }
    }

    private static Map<String, Object> snapshot(Society s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", s.getName());
        values.put("addressLine1", s.getAddressLine1());
        values.put("addressLine2", s.getAddressLine2());
        values.put("city", s.getCity());
        values.put("state", s.getState());
        values.put("pinCode", s.getPinCode());
        values.put("country", s.getCountry());
        values.put("contactPhone", s.getContactPhone());
        values.put("contactEmail", s.getContactEmail());
        values.put("timeZone", s.getTimeZone());
        return values;
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            if (!Objects.equals(before.get(key), value)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", before.get(key));
                change.put("to", value);
                changes.put(key, change);
            }
        });
        return changes;
    }

    private static List<String> cleanList(List<String> values) {
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }
}
