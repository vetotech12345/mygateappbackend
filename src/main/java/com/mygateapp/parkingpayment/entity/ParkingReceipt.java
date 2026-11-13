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

/** Immutable receipt issued when a payment is confirmed. */
@Entity
@Immutable
@Table(name = "parking_receipts")
@Getter
@Setter
public class ParkingReceipt extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "payer_name", length = 80)
    private String payerName;

    @Column(name = "society_name", nullable = false, length = 120)
    private String societyName;

    @Column(name = "vehicle_number", length = 15)
    private String vehicleNumber;

    /** JSON array of {label, amount}. */
    @Column(name = "lines", nullable = false)
    private String lines;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(name = "tax_label", length = 30)
    private String taxLabel;

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "issued_by_name", length = 80)
    private String issuedByName;
}
