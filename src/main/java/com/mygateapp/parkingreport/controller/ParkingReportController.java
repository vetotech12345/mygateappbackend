package com.mygateapp.parkingreport.controller;

import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parkingreport.dto.ParkingReportDtos;
import com.mygateapp.parkingreport.service.ParkingReportService;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking/reports")
@Tag(name = "Parking reports", description = "Society-scoped parking reports (administrators; PARKING_REPORT_VIEW)")
@PreAuthorize("hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')")
public class ParkingReportController {

    private final ParkingReportService reports;

    public ParkingReportController(ParkingReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/overview")
    @Operation(summary = "Slots (allocated / occupied / reserved / available), vehicles, permits, overstays, payments")
    public ParkingReportDtos.Overview overview() {
        return reports.overview();
    }

    @GetMapping("/occupancy")
    @Operation(summary = "Live occupancy per area and per usage")
    public ParkingReportDtos.OccupancyReport occupancy() {
        return reports.occupancy();
    }

    @GetMapping("/sessions")
    @Operation(summary = "Sessions that entered in the range (society time zone, inclusive, default last 7 days), with "
            + "usage by category and a page of sessions")
    public ParkingReportDtos.SessionsReport sessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return reports.sessions(from, to, category, status, page, size);
    }

    @GetMapping("/revenue")
    @Operation(summary = "Collections, refunds, waivers, pending payments; by method, purpose, day and month")
    public ParkingReportDtos.RevenueReport revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.revenue(from, to);
    }
}
