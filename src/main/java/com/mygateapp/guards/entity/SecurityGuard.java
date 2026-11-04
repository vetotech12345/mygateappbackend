package com.mygateapp.guards.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.users.entity.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A user's guard profile in one society. */
@Entity
@Table(name = "security_guards")
@Getter
@Setter
public class SecurityGuard extends VersionedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "employee_code", length = 30)
    private String employeeCode;

    @Column(name = "agency_name", length = 100)
    private String agencyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "on_duty", nullable = false)
    private boolean onDuty;

    @Column(name = "duty_changed_at")
    private Instant dutyChangedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "guard_permissions", joinColumns = @JoinColumn(name = "guard_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 30)
    private Set<GuardPermission> permissions = EnumSet.noneOf(GuardPermission.class);
}
