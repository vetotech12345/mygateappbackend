package com.mygateapp.society.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** "User holds role X in society Y". Resident flats and guard details live in their own tables. */
@Entity
@Table(name = "society_memberships")
@Getter
@Setter
public class SocietyMembership extends VersionedEntity {

    public enum Status { PENDING, ACTIVE, INACTIVE }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false, length = 40)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;
}
