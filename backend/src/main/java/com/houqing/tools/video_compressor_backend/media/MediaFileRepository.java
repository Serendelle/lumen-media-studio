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

    /** [role, count, totalBytes] per role for one owner. */
    @org.springframework.data.jpa.repository.Query(
            "select f.role, count(f), coalesce(sum(f.sizeBytes), 0) from MediaFile f where f.ownerId = :ownerId group by f.role")
    List<Object[]> usageByRole(@org.springframework.data.repository.query.Param("ownerId") long ownerId);

    /** Earliest expiry among the owner's files (null when there are none). */
    @org.springframework.data.jpa.repository.Query("select min(f.expiresAt) from MediaFile f where f.ownerId = :ownerId")
    Long nextExpiry(@org.springframework.data.repository.query.Param("ownerId") long ownerId);
}
