package com.mygateapp.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/** Optimistic locking for aggregates that can be changed concurrently. */
@MappedSuperclass
public abstract class VersionedEntity extends AuditedEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public long getVersion() {
        return version;
    }
}
