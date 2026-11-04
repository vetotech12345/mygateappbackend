package com.mygateapp.complaints.repository;

import com.mygateapp.complaints.entity.ComplaintStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintStatusHistoryRepository extends JpaRepository<ComplaintStatusHistory, UUID> {

    List<ComplaintStatusHistory> findByComplaintIdOrderByChangedAtAsc(UUID complaintId);
}
