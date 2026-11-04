package com.mygateapp.deliveries.entity;

import com.mygateapp.common.GateEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "delivery_events")
@Getter
@Setter
public class DeliveryEvent extends GateEvent {

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;
}
