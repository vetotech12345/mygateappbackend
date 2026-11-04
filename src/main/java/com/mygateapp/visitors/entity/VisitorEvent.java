package com.mygateapp.visitors.entity;

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
@Table(name = "visitor_events")
@Getter
@Setter
public class VisitorEvent extends GateEvent {

    @Column(name = "visitor_id", nullable = false, updatable = false)
    private UUID visitorId;
}
