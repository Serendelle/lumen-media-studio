package com.houqing.tools.video_compressor_backend.lesson;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LessonRepository extends JpaRepository<LessonEntity, String> {
    Optional<LessonEntity> findByIdAndOwnerId(String id, long ownerId);
    Page<LessonEntity> findByOwnerId(long ownerId, Pageable pageable);
}
