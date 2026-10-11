package com.houqing.tools.video_compressor_backend.media;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaFileRepository extends JpaRepository<MediaFile, String> {
    Optional<MediaFile> findByIdAndOwnerId(String id, long ownerId);
    Page<MediaFile> findByOwnerIdAndRole(long ownerId, String role, Pageable pageable);
    List<MediaFile> findByExpiresAtLessThan(long cutoff);
}
