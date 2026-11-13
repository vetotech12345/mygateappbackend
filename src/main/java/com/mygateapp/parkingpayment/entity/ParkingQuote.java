package com.mygateapp.parkingpayment.entity;

import com.mygateapp.common.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** A server-computed charge. Payments reference a quote so the amount never comes from the client. */
@Entity
@Immutable
@Table(name = "parking_quotes")
@Getter
@Setter
public class ParkingQuote extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "rate_plan_id", nullable = false)
    private UUID ratePlanId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "permit_id")
    private UUID permitId;

    @Column(name = "period_from", nullable = false)
    private Instant periodFrom;

    @Column(name = "period_until", nullable = false)
    private Instant periodUntil;

    @Column(name = "billable_minutes", nullable = false)
    private int billableMinutes;

    /** JSON array of {label, amount}. */
    @Column(name = "lines", nullable = false)
    private String lines;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(name = "tax_label", length = 30)
    private String taxLabel;

    @Column(name = "already_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal alreadyPaid;

    /** Amount due: subtotal + tax − already paid. */
    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
