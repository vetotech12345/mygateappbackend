package com.mygateapp.audit.service;

import com.mygateapp.audit.dto.AuditLogResponse;
import com.mygateapp.audit.entity.AuditLog;
import com.mygateapp.audit.repository.AuditLogRepository;
import com.mygateapp.common.Pages;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.AuthenticatedUser;
import com.mygateapp.security.Permission;
import com.mygateapp.users.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/** Writes the audit trail for administrative changes (in the caller's transaction) and serves it to admins. */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final UserRepository users;
    private final AccessService access;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, UserRepository users, AccessService access,
                        ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.access = access;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Records an action by the current user. {@code details} must not contain secrets; values are serialised to JSON.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID societyId, String action, String entityType, UUID entityId, String summary,
                       Map<String, ?> details) {
        AuditLog log = new AuditLog();
        log.setSocietyId(societyId);
        UUID actorId = currentUserIdOrNull();
        log.setActorUserId(actorId);
        if (actorId != null) {
            users.findById(actorId).ifPresent(u -> log.setActorName(u.displayName()));
        }
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setSummary(summary.length() > 300 ? summary.substring(0, 300) : summary);
        log.setDetails(details == null || details.isEmpty() ? null : objectMapper.writeValueAsString(details));
        log.setIpAddress(clientIp());
        log.setOccurredAt(clock.instant());
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(UUID societyId, String action, String entityType, UUID entityId,
                                                 UUID actorId, LocalDate from, LocalDate to, Integer page, Integer size) {
        boolean allSocieties = societyId == null && access.hasPlatformPermission(Permission.AUDIT_VIEW);
        UUID scope = null;
        if (!allSocieties) {
            scope = access.resolveSocietyId(societyId);
            access.require(scope, Permission.AUDIT_VIEW);
        }
        Instant fromInstant = from == null ? TimeRanges.MIN : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toInstant = to == null ? TimeRanges.MAX : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return PageResponse.of(repository.search(allSocieties, scope, action, entityType, entityId, actorId,
                fromInstant, toInstant, Pages.of(page, size)), AuditLogResponse::of);
    }

    private static UUID currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user.userId() : null;
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
