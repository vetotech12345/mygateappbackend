package com.mygateapp.flats.entity;

import com.mygateapp.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Optional subdivision of a tower. Societies without wings simply have none. */
@Entity
@Table(name = "wings")
@Getter
@Setter
public class Wing extends AuditedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "tower_id", nullable = false)
    private UUID towerId;

    @Column(name = "name", nullable = false, length = 40)
    private String name;
}
