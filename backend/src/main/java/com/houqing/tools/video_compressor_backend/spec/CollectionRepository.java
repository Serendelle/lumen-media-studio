package com.houqing.tools.video_compressor_backend.spec;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollectionRepository extends JpaRepository<CollectionEntities.CollectionLink, String> {
    Optional<CollectionEntities.CollectionLink> findByTokenHash(String tokenHash);
    List<CollectionEntities.CollectionLink> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
