package com.mygateapp.reports.controller;

import com.mygateapp.activity.service.ActivityService;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.reports.dto.DashboardDtos;
import com.mygateapp.reports.service.DashboardService;
import com.mygateapp.reports.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboards & reports", description = "Resident / guard dashboards and activity feeds, admin dashboard and reports")
public class DashboardController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";

    private final DashboardService dashboards;
    private final ReportService reports;

    public DashboardController(DashboardService dashboards, ReportService reports) {
        this.dashboards = dashboards;
        this.reports = reports;
    }

    @GetMapping("/residents/dashboard")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Resident home: pending approvals, today's counts, latest notices and recent activity")
    public DashboardDtos.ResidentDashboard residentDashboard() {
        return dashboards.resident();
    }

    @GetMapping("/residents/activity")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Activity of the resident's flats; kind = VISITOR | DELIVERY | STAFF | COMPLAINT")
    public PageResponse<ActivityService.ActivityItem> residentActivity(@RequestParam(required = false) ActivityService.Kind kind,
                                                                       @RequestParam(required = false) Integer page,
                                                                       @RequestParam(required = false) Integer size) {
        return dashboards.residentActivity(kind, page, size);
    }

    @GetMapping("/guards/dashboard")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Guard home: today's gate counts and recent gate activity of the society")
    public DashboardDtos.GuardDashboard guardDashboard() {
        return dashboards.guard();
    }

    @GetMapping("/guards/activity")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Gate activity of the guard's society; kind = VISITOR | DELIVERY | STAFF")
    public PageResponse<ActivityService.ActivityItem> guardActivity(@RequestParam(required = false) ActivityService.Kind kind,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        return dashboards.guardActivity(kind, page, size);
    }

    @GetMapping("/admin/dashboard")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Society metrics for the admin panel")
    public DashboardDtos.AdminDashboard adminDashboard(@RequestParam(required = false) UUID societyId) {
        return dashboards.admin(societyId);
    }

    @GetMapping("/reports/visitors")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Visitor report for a date range (society time zone, inclusive, default last 7 days)")
    public DashboardDtos.VisitorReport visitorReport(
            @RequestParam(required = false) UUID societyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.visitors(societyId, from, to);
    }

    @GetMapping("/reports/deliveries")
    @PreAuthorize(ADMINS)
    public DashboardDtos.DeliveryReport deliveryReport(
            @RequestParam(required = false) UUID societyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.deliveries(societyId, from, to);
    }

    @GetMapping("/reports/complaints")
    @PreAuthorize(ADMINS)
    public DashboardDtos.ComplaintReport complaintReport(
            @RequestParam(required = false) UUID societyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.complaints(societyId, from, to);
    }
}
