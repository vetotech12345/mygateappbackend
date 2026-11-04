package com.mygateapp.audit.dto;

import com.mygateapp.audit.entity.AuditLog;
import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID societyId,
        UUID actorUserId,
        String actorName,
        String action,
        String entityType,
        UUID entityId,
        String summary,
        String details,
        String ipAddress,
        Instant occurredAt) {

    public static AuditLogResponse of(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getSocietyId(), log.getActorUserId(), log.getActorName(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getSummary(), log.getDetails(),
                log.getIpAddress(), log.getOccurredAt());
    }
}
