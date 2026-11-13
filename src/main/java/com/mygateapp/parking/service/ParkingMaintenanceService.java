package com.mygateapp.parking.service;

import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.entity.ParkingMovement;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.MovementType;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingMovementRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingRequestRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.repository.ParkingVerificationRepository;
import com.mygateapp.parkingpayment.repository.QuoteRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Row-level steps of {@code ParkingJobs}; each runs in its own transaction under a row lock (idempotent). */
@Service
public class ParkingMaintenanceService {

    static final Duration TRANSIENT_RETENTION = Duration.ofDays(7);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final ParkingAllocationRepository allocations;
    private final ParkingPermitRepository permits;
    private final ParkingSessionRepository sessions;
    private final ParkingRequestRepository requests;
    private final ParkingMovementRepository movements;
    private final ParkingVerificationRepository verifications;
    private final QuoteRepository quotes;
    private final ParkingAllocationService allocationService;
    private final ParkingSettingsStore settingsStore;
    private final ParkingSupport support;

    public ParkingMaintenanceService(ParkingAllocationRepository allocations, ParkingPermitRepository permits,
                                     ParkingSessionRepository sessions, ParkingRequestRepository requests,
                                     ParkingMovementRepository movements, ParkingVerificationRepository verifications,
                                     QuoteRepository quotes, ParkingAllocationService allocationService,
                                     ParkingSettingsStore settingsStore, ParkingSupport support) {
        this.allocations = allocations;
        this.permits = permits;
        this.sessions = sessions;
        this.requests = requests;
        this.movements = movements;
        this.verifications = verifications;
        this.quotes = quotes;
        this.allocationService = allocationService;
        this.settingsStore = settingsStore;
        this.support = support;
    }

    @Transactional
    public boolean expireAllocation(UUID id, Instant now) {
        return allocations.findByIdForUpdate(id)
                .filter(a -> a.getStatus().live() && a.getValidUntil() != null && !a.getValidUntil().isAfter(now))
                .map(a -> {
                    allocationService.end(a, AllocationStatus.EXPIRED, "Allocation period ended");
                    return true;
                }).orElse(false);
    }

    @Transactional
    public boolean expirePermit(UUID id, Instant now) {
        return permits.findByIdForUpdate(id)
                .filter(p -> ParkingAllocationService.OPEN_PERMITS.contains(p.getStatus()) && p.getValidUntil() != null
                        && !p.getValidUntil().isAfter(now))
                .map(p -> {
                    p.setStatus(PermitStatus.EXPIRED);
                    return true;
                }).orElse(false);
    }

    /** Records an overstay once: OVERSTAY movement and a notification to the flat and on-duty guards. */
    @Transactional
    public boolean reportOverstay(UUID id, Instant now) {
        ParkingSession session = sessions.findByIdForUpdate(id).orElse(null);
        if (session == null || session.getOverstayNotifiedAt() != null) {
            return false;
        }
        int grace = settingsStore.settingsFor(session.getSocietyId()).getFreeGraceMinutes();
        if (!session.isOverstay(now, grace)) {
            return false;
        }
        session.setOverstayNotifiedAt(now);
        ParkingMovement movement = new ParkingMovement();
        movement.setSocietyId(session.getSocietyId());
        movement.setSessionId(session.getId());
        movement.setVehicleNumber(session.getVehicleNumber());
        movement.setEventType(MovementType.OVERSTAY);
        movement.setActorName("System");
        movement.setDetails("Allowed until " + session.getValidUntil());
        movement.setOccurredAt(now);
        movements.save(movement);
        support.notifyFlat(session.getFlatId(), session.getSocietyId(), "Parking overstay",
                session.getVehicleNumber() + " is parked beyond its allowed time.",
                Notification.ReferenceType.PARKING_SESSION, session.getId());
        support.notifyGuards(session.getSocietyId(), "Parking overstay",
                session.getVehicleNumber() + " has overstayed its parking window.",
                Notification.ReferenceType.PARKING_SESSION, session.getId());
        return true;
    }

    @Transactional
    public boolean remindPermit(UUID id, Instant now) {
        ParkingPermit permit = permits.findByIdForUpdate(id).orElse(null);
        if (permit == null || permit.getExpiryRemindedAt() != null || permit.getStatus() != PermitStatus.ACTIVE) {
            return false;
        }
        permit.setExpiryRemindedAt(now);
        String day = DAY.withZone(support.zone(permit.getSocietyId())).format(permit.getValidUntil());
        support.notifyFlat(permit.getFlatId(), permit.getSocietyId(), "Parking permit expiring",
                "Permit " + permit.getPermitNumber() + " for " + permit.getVehicleNumber() + " ends on " + day + ".",
                Notification.ReferenceType.PARKING_PERMIT, permit.getId());
        return true;
    }

    /** Removes visitor personal data from old visitor requests and deletes old transient rows. */
    @Transactional
    public int[] retention(Instant now) {
        int anonymized = requests.anonymizeVisitorData(now);
        int verificationsDeleted = verifications.deleteUnusedBefore(now.minus(TRANSIENT_RETENTION));
        int quotesDeleted = quotes.deleteUnusedBefore(now.minus(TRANSIENT_RETENTION));
        return new int[] {anonymized, verificationsDeleted, quotesDeleted};
    }
}
