package com.mygateapp.residents.repository;

import com.mygateapp.residents.entity.FamilyMember;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, UUID> {

    List<FamilyMember> findByFlatMembershipIdInOrderByCreatedAt(Collection<UUID> membershipIds);

    long countByFlatMembershipId(UUID membershipId);
}
