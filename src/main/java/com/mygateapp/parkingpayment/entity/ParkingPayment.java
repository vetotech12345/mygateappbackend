package com.mygateapp.parkingpayment.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentPurpose;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Payment for a parking session or a permit. Every status change is also an immutable payment event. */
@Entity
@Table(name = "parking_payments")
@Getter
@Setter
public class ParkingPayment extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private PaymentPurpose purpose;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "permit_id", updatable = false)
    private UUID permitId;

    @Column(name = "quote_id", updatable = false)
    private UUID quoteId;

    @Column(name = "flat_id", updatable = false)
    private UUID flatId;

    @Column(name = "payer_name", length = 80)
    private String payerName;

    @Column(name = "vehicle_number", length = 15)
    private String vehicleNumber;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20)
    private PaymentMethod method;

    @Column(name = "reference", length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_by_name", length = 80)
    private String recordedByName;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_by_name", length = 80)
    private String confirmedByName;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "refunded_by")
    private UUID refundedBy;

    @Column(name = "status_reason", length = 200)
    private String statusReason;
}
