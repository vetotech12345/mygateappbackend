package com.mygateapp.reports.dto;

import com.mygateapp.activity.service.ActivityService.ActivityItem;
import com.mygateapp.deliveries.dto.DeliveryDtos.DeliveryResponse;
import com.mygateapp.notices.dto.NoticeDtos.NoticeResponse;
import com.mygateapp.visitors.dto.VisitorDtos.VisitorResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** {@code GET /residents/dashboard} — Flutter {@code ResidentDashboard}. */
    public record ResidentDashboard(
            List<VisitorResponse> pendingVisitors,
            List<DeliveryResponse> pendingDeliveries,
            long todayVisitorCount,
            long insideNowCount,
            long todayDeliveryCount,
            long staffInsideCount,
            long openComplaintCount,
            long unreadNoticeCount,
            List<NoticeResponse> latestNotices,
            List<ActivityItem> recentActivity) {
    }

    /** {@code GET /guards/dashboard} — Flutter {@code GuardDashboard}. */
    public record GuardDashboard(
            long todayVisitorCount,
            long pendingApprovalCount,
            long approvedWaitingCount,
            long insideNowCount,
            long deliveriesTodayCount,
            long staffInsideCount,
            List<ActivityItem> recentActivity) {
    }

    /** {@code GET /admin/dashboard} — society metrics for the admin panel. */
    public record AdminDashboard(
            UUID societyId,
            LocalDate date,
            long visitorsToday,
            long pendingVisitorApprovals,
            long visitorsInside,
            long deliveriesToday,
            long pendingDeliveryApprovals,
            long openComplaints,
            long inProgressComplaints,
            long pendingResidentRequests,
            long activeResidents,
            long activeGuards,
            long guardsOnDuty,
            long staffInside,
            long totalFlats,
            long occupiedFlats,
            Instant generatedAt) {
    }

    public record DailyCount(LocalDate date, long total, Map<String, Long> breakdown) {
    }

    public record VisitorReport(LocalDate from, LocalDate to, long total, long entered, long rejected, long invited,
                                Map<String, Long> byStatus, Map<String, Long> byType, List<DailyCount> daily) {
    }

    public record DeliveryReport(LocalDate from, LocalDate to, long total, long entered, long rejected, long leftAtGate,
                                 Map<String, Long> byCategory, Map<String, Long> byStatus, List<DailyCount> daily) {
    }

    public record ComplaintReport(LocalDate from, LocalDate to, long raised, long resolved, Double averageResolutionHours,
                                  long currentlyOpen, Map<String, Long> byStatus, Map<String, Long> byCategory,
                                  List<DailyCount> daily) {
    }
}
