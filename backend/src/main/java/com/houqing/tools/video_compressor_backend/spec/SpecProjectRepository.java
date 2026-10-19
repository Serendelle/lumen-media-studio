package com.houqing.tools.video_compressor_backend.spec;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpecProjectRepository extends JpaRepository<SpecEntities.SpecProject, String> {
    Optional<SpecEntities.SpecProject> findByIdAndOwnerId(String id, long ownerId);
    Page<SpecEntities.SpecProject> findByOwnerId(long ownerId, Pageable pageable);
}
