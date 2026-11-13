package com.mygateapp.society.controller;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.flats.entity.Tower;
import com.mygateapp.flats.repository.TowerRepository;
import com.mygateapp.flats.repository.WingRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.service.SocietyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /societies/my}: the current society (membership or {@code X-Society-Id}) for any member. The admin-only
 * {@code /societies/{id}} endpoints are unchanged.
 */
@RestController
@Tag(name = "Societies")
public class MySocietyController {

    public record WingSummary(UUID id, String name) {
    }

    public record TowerSummary(UUID id, String code, String name, Integer floorCount, List<WingSummary> wings) {
    }

    /** Matches the Flutter {@code SocietyProfile}. {@code officeHours} is not stored yet (always null). */
    public record MySocietyResponse(UUID id, String code, String name, String addressLine1, String addressLine2,
                                    String city, String state, String pinCode, String country, String contactPhone,
                                    String contactEmail, String officeHours, String timeZone, List<TowerSummary> towers) {
    }

    private final AccessService access;
    private final SocietyService societies;
    private final TowerRepository towers;
    private final WingRepository wings;

    public MySocietyController(AccessService access, SocietyService societies, TowerRepository towers, WingRepository wings) {
        this.access = access;
        this.societies = societies;
        this.towers = towers;
        this.wings = wings;
    }

    @GetMapping("/api/v1/societies/my")
    @PreAuthorize("hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "The caller's current society with towers and wings (residents, guards, admins)")
    @Transactional(readOnly = true)
    public MySocietyResponse mySociety() {
        UUID societyId = access.currentSocietyId();
        Society s = societies.requireVisible(societyId);
        List<TowerSummary> towerList = towers.findBySocietyIdOrderByCode(societyId).stream()
                .filter(t -> t.getStatus() == ActiveStatus.ACTIVE)
                .map(this::tower)
                .toList();
        return new MySocietyResponse(s.getId(), s.getCode(), s.getName(), s.getAddressLine1(), s.getAddressLine2(),
                s.getCity(), s.getState(), s.getPinCode(), s.getCountry(), s.getContactPhone(), s.getContactEmail(), null,
                s.getTimeZone(), towerList);
    }

    private TowerSummary tower(Tower t) {
        return new TowerSummary(t.getId(), t.getCode(), t.getName(), t.getFloorCount(),
                wings.findByTowerIdOrderByName(t.getId()).stream().map(w -> new WingSummary(w.getId(), w.getName())).toList());
    }
}
