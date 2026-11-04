package com.mygateapp.society.entity;

import com.mygateapp.common.TextListConverter;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/** Per-society rules used by the visitor and delivery workflows. One row per society. */
@Entity
@Table(name = "society_settings")
@Getter
@Setter
public class SocietySettings {

    /** Built-in defaults: food, grocery and other deliveries need resident approval; parcels do not. */
    public static final Map<DeliveryCategory, Boolean> DEFAULT_DELIVERY_RULES = Map.of(
            DeliveryCategory.FOOD, true,
            DeliveryCategory.GROCERY, true,
            DeliveryCategory.COURIER, false,
            DeliveryCategory.ECOMMERCE, false,
            DeliveryCategory.OTHER, true);

    public static final List<String> DEFAULT_VISIT_PURPOSES =
            List.of("Personal visit", "Delivery", "Repair / service", "Cab pickup", "Meeting");

    @Id
    @Column(name = "society_id")
    private UUID societyId;

    @Column(name = "max_invite_validity_hours", nullable = false)
    private int maxInviteValidityHours = 168;

    @Column(name = "approval_timeout_seconds", nullable = false)
    private int approvalTimeoutSeconds = 180;

    @Column(name = "pending_visitor_expiry_minutes", nullable = false)
    private int pendingVisitorExpiryMinutes = 120;

    @Column(name = "visitor_retention_days", nullable = false)
    private int visitorRetentionDays = 365;

    @Convert(converter = TextListConverter.class)
    @Column(name = "visit_purposes")
    private List<String> visitPurposes = new ArrayList<>();

    @Convert(converter = TextListConverter.class)
    @Column(name = "delivery_companies")
    private List<String> deliveryCompanies = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "society_delivery_rules", joinColumns = @JoinColumn(name = "society_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "category", length = 20)
    @Column(name = "requires_approval", nullable = false)
    private Map<DeliveryCategory, Boolean> deliveryApprovalRules = new EnumMap<>(DeliveryCategory.class);

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean deliveryRequiresApproval(DeliveryCategory category) {
        return deliveryApprovalRules.getOrDefault(category, DEFAULT_DELIVERY_RULES.getOrDefault(category, true));
    }
}
