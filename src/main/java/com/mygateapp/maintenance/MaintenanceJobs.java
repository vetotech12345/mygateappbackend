package com.mygateapp.maintenance;

import com.mygateapp.auth.repository.RefreshSessionRepository;
import com.mygateapp.deliveries.service.DeliveryService;
import com.mygateapp.visitors.repository.VisitorRepository;
import com.mygateapp.visitors.service.VisitorService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs. Each job is idempotent and works row by row under a lock, so running several application
 * instances at once is safe (a row handled by one instance is simply skipped by the other).
 */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);
    static final Duration NOTIFICATION_RETENTION = Duration.ofDays(180);
    static final Duration SESSION_CLEANUP_GRACE = Duration.ofDays(7);
    /** At most 100 x 500 visits per run; the rest is picked up the next night. */
    static final int MAX_RETENTION_BATCHES = 100;

    private final VisitorRepository visitors;
    private final VisitorService visitorService;
    private final DeliveryService deliveryService;
    private final RefreshSessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MaintenanceJobs(VisitorRepository visitors, VisitorService visitorService, DeliveryService deliveryService,
                           RefreshSessionRepository sessions, JdbcTemplate jdbc, Clock clock) {
        this.visitors = visitors;
        this.visitorService = visitorService;
        this.deliveryService = deliveryService;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Invitations past their window become EXPIRED; unanswered walk-in requests expire per society setting. */
    @Scheduled(fixedDelayString = "${app.jobs.visitor-expiry-delay:PT1M}", initialDelayString = "PT30S")
    public int expireVisitors() {
        Instant now = clock.instant();
        int expired = 0;
        for (UUID id : visitors.findExpiredInvitationIds(now)) {
            if (safely(() -> visitorService.expireInvitation(id, now))) {
                expired++;
            }
        }
        for (UUID id : visitors.findStalePendingIds(now)) {
            if (safely(() -> visitorService.expirePending(id))) {
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} visitor requests/invitations", expired);
        }
        return expired;
    }

    /**
     * Data retention: visitor and delivery-agent personal data older than the society's retention period is removed
     * (history rows remain for audit, without names). Old notifications and dead sessions are deleted.
     */
    @Scheduled(cron = "${app.jobs.retention-cron:0 30 3 * * *}")
    public void applyRetention() {
        Instant now = clock.instant();
        int anonymized = 0;
        List<UUID> batch;
        int batches = 0;
        do {
            batch = visitors.findIdsDueForAnonymization(now);
            int succeeded = 0;
            for (UUID id : batch) {
                if (safely(() -> {
                    visitorService.anonymize(id);
                    return true;
                })) {
                    succeeded++;
                }
            }
            anonymized += succeeded;
            // A batch where nothing could be anonymised would come back unchanged: stop instead of looping.
            if (succeeded == 0) {
                break;
            }
        } while (batch.size() == 500 && ++batches < MAX_RETENTION_BATCHES);
        int deliveries = deliveryService.anonymizeExpired(now);
        int notifications = deleteOldNotifications(now);
        int expiredSessions = deleteExpiredSessions(now);
        log.info("Retention: {} visitors and {} deliveries anonymised, {} notifications and {} sessions deleted",
                anonymized, deliveries, notifications, expiredSessions);
    }

    int deleteOldNotifications(Instant now) {
        return jdbc.update("DELETE FROM notifications WHERE created_at < ?",
                java.sql.Timestamp.from(now.minus(NOTIFICATION_RETENTION)));
    }

    int deleteExpiredSessions(Instant now) {
        return sessions.deleteExpiredBefore(now.minus(SESSION_CLEANUP_GRACE));
    }

    private static boolean safely(java.util.function.Supplier<Boolean> action) {
        try {
            return action.get();
        } catch (RuntimeException ex) {
            log.warn("Maintenance step failed: {}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
