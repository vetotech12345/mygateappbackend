package com.mygateapp.parking.service;

import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingRequestRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Parking background work: expiry of requests, allocations and permits; overstay detection; permit expiry
 * reminders; retention. Every step is idempotent and row-locked, so several instances may run it at once.
 */
@Component
public class ParkingJobs {

    private static final Logger log = LoggerFactory.getLogger(ParkingJobs.class);
    static final Duration REMINDER_HORIZON = Duration.ofDays(3);

    private final ParkingRequestRepository requests;
    private final ParkingAllocationRepository allocations;
    private final ParkingPermitRepository permits;
    private final ParkingSessionRepository sessions;
    private final ParkingRequestService requestService;
    private final ParkingMaintenanceService maintenance;
    private final Clock clock;

    public ParkingJobs(ParkingRequestRepository requests, ParkingAllocationRepository allocations,
                       ParkingPermitRepository permits, ParkingSessionRepository sessions,
                       ParkingRequestService requestService, ParkingMaintenanceService maintenance, Clock clock) {
        this.requests = requests;
        this.allocations = allocations;
        this.permits = permits;
        this.sessions = sessions;
        this.requestService = requestService;
        this.maintenance = maintenance;
        this.clock = clock;
    }

    /** Returns the number of rows changed (used by tests). */
    @Scheduled(fixedDelayString = "${app.jobs.parking-delay:PT1M}", initialDelayString = "PT45S")
    public int run() {
        Instant now = clock.instant();
        int changed = 0;
        for (UUID id : requests.findExpirableIds(now)) {
            changed += safely(() -> requestService.expire(id, now));
        }
        for (UUID id : allocations.findEndedIds(now)) {
            changed += safely(() -> maintenance.expireAllocation(id, now));
        }
        for (UUID id : permits.findEndedIds(now)) {
            changed += safely(() -> maintenance.expirePermit(id, now));
        }
        for (UUID id : sessions.findUnreportedOverstayIds(now)) {
            changed += safely(() -> maintenance.reportOverstay(id, now));
        }
        for (ParkingPermit permit : permits.findExpiringUnreminded(now, now.plus(REMINDER_HORIZON))) {
            changed += safely(() -> maintenance.remindPermit(permit.getId(), now));
        }
        if (changed > 0) {
            log.info("Parking maintenance changed {} rows", changed);
        }
        return changed;
    }

    @Scheduled(cron = "${app.jobs.parking-retention-cron:0 45 3 * * *}")
    public void retention() {
        try {
            int[] result = maintenance.retention(clock.instant());
            log.info("Parking retention: {} visitor requests anonymised, {} verifications and {} quotes deleted",
                    result[0], result[1], result[2]);
        } catch (RuntimeException ex) {
            log.warn("Parking retention failed: {}", ex.getClass().getSimpleName());
        }
    }

    private static int safely(Supplier<Boolean> step) {
        try {
            return Boolean.TRUE.equals(step.get()) ? 1 : 0;
        } catch (RuntimeException ex) {
            log.warn("Parking maintenance step failed: {}", ex.getClass().getSimpleName());
            return 0;
        }
    }
}
