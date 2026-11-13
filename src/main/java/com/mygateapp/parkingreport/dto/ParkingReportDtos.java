package com.mygateapp.parkingreport.dto;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos.SessionResponse;
import com.mygateapp.parking.dto.ParkingRefs.AreaRef;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ParkingReportDtos {

    private ParkingReportDtos() {
    }

    public record SlotCounts(int total, int inService, int outOfService, int allocated, int unallocated, int occupied,
                             int vacant, int reserved, int available) {
    }

    public record Overview(
            Instant generatedAt, SlotCounts slots, long registeredVehicles, long pendingVehicles, long vehiclesInside,
            long activeAllocations, long activePermits, long expiredPermits, long pendingRequests, long overstays,
            long todayEntries, long todayExits, long pendingPayments, Money pendingAmount, Money revenueToday,
            Money revenueThisMonth) {
    }

    public record AreaOccupancy(AreaRef area, String code, ParkingUsage usage, boolean active, SlotCounts slots,
                                double occupancyPercent) {
    }

    public record UsageOccupancy(ParkingUsage usage, SlotCounts slots) {
    }

    public record OccupancyReport(Instant generatedAt, SlotCounts total, List<AreaOccupancy> areas,
                                  List<UsageOccupancy> usages) {
    }

    /**
     * {@code byCategory}: sessions that entered in the range per category (resident / visitor / paid usage).
     * {@code averageDurationMinutes} over completed sessions.
     */
    public record SessionsReport(LocalDate from, LocalDate to, long totalSessions, Map<String, Long> byCategory,
                                 long paidSessions, long overrideEntries, long overstays, long averageDurationMinutes,
                                 PageResponse<SessionResponse> sessions) {
    }

    public record AmountCount(String key, Money amount, long count) {
    }

    public record RevenueReport(LocalDate from, LocalDate to, String currency, Money collected, long collectedCount,
                                Money refunded, Money waived, Money net, Money pendingAmount, long pendingCount,
                                List<AmountCount> byMethod, List<AmountCount> byPurpose, List<AmountCount> daily,
                                List<AmountCount> monthly) {
    }
}
