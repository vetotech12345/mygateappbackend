package com.mygateapp.residents.entity;

import com.mygateapp.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Household member without a login, attached to a resident's flat membership. */
@Entity
@Table(name = "family_members")
@Getter
@Setter
public class FamilyMember extends AuditedEntity {

    @Column(name = "flat_membership_id", nullable = false)
    private UUID flatMembershipId;

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "relation", nullable = false, length = 30)
    private String relation;

    @Column(name = "mobile", length = 15)
    private String mobile;
}
