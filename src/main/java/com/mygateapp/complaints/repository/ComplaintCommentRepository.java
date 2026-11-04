package com.mygateapp.complaints.repository;

import com.mygateapp.complaints.entity.ComplaintComment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintCommentRepository extends JpaRepository<ComplaintComment, UUID> {

    List<ComplaintComment> findByComplaintIdOrderByCreatedAtAsc(UUID complaintId);
}
