package com.houqing.tools.video_compressor_backend.spec;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SpecItemRepository extends JpaRepository<SpecEntities.SpecItem, String> {
    List<SpecEntities.SpecItem> findByProjectIdOrderByCreatedAtAsc(String projectId);
    Optional<SpecEntities.SpecItem> findByIdAndProjectId(String id, String projectId);
    Optional<SpecEntities.SpecItem> findByProjectIdAndFileId(String projectId, String fileId);
    long countByProjectId(String projectId);

    @Transactional
    void deleteByProjectId(String projectId);
}
