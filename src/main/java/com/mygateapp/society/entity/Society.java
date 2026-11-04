package com.mygateapp.society.entity;

import com.mygateapp.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "societies")
@Getter
@Setter
public class Society extends VersionedEntity {

    public enum Status { ACTIVE, INACTIVE }

    public enum SubscriptionPlan { TRIAL, BASIC, PREMIUM }

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "state", nullable = false, length = 80)
    private String state;

    @Column(name = "pin_code", nullable = false, length = 10)
    private String pinCode;

    @Column(name = "country", nullable = false, length = 60)
    private String country;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone = "Asia/Kolkata";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_plan", nullable = false, length = 20)
    private SubscriptionPlan subscriptionPlan = SubscriptionPlan.BASIC;

    @Column(name = "subscription_valid_until")
    private LocalDate subscriptionValidUntil;

    @Column(name = "created_by")
    private UUID createdBy;

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
