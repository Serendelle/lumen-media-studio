package com.houqing.tools.video_compressor_backend.spec;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpecProjectRepository extends JpaRepository<SpecEntities.SpecProject, String> {
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "update spec_projects set updated_at = updated_at where id = :id", nativeQuery = true)
    int lockProject(@org.springframework.data.repository.query.Param("id") String id);
    Optional<SpecEntities.SpecProject> findByIdAndOwnerId(String id, long ownerId);
    Page<SpecEntities.SpecProject> findByOwnerId(long ownerId, Pageable pageable);
}
