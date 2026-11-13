package com.mygateapp.parkingpayment.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentEventType;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
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
import org.hibernate.annotations.Immutable;

/** Append-only payment history: who did what, when, with which amount and reason. */
@Entity
@Immutable
@Table(name = "parking_payment_events")
@Getter
@Setter
public class ParkingPaymentEvent extends IdentifiedEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private PaymentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20)
    private PaymentMethod method;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_name", length = 80)
    private String actorName;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
