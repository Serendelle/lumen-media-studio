package com.houqing.tools.video_compressor_backend.media;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    Optional<TaskEntity> findByIdAndOwnerId(String id, long ownerId);
    Page<TaskEntity> findByOwnerId(long ownerId, Pageable pageable);
    List<TaskEntity> findByStatusIn(List<String> statuses);
    int countByOwnerIdAndStatusIn(long ownerId, List<String> statuses);
    Optional<TaskEntity> findByOwnerIdAndIdempotencyKey(long ownerId, String idempotencyKey);
}
