package com.houqing.tools.video_compressor_backend.media;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    Optional<TaskEntity> findByIdAndOwnerId(String id, long ownerId);
    Optional<TaskEntity> findByIdAndOwnerIdAndHistoryDeletedAtIsNull(String id, long ownerId);
    List<TaskEntity> findByOwnerIdAndIdInAndHistoryDeletedAtIsNull(long ownerId, List<String> ids);
    @Query("select t from TaskEntity t where t.ownerId = :ownerId and t.historyDeletedAt is null")
    Page<TaskEntity> findByOwnerId(long ownerId, Pageable pageable);
    @Query("select t from TaskEntity t where t.ownerId = :ownerId and t.historyDeletedAt is null and t.status in :statuses")
    Page<TaskEntity> findByOwnerIdAndStatusIn(long ownerId, List<String> statuses, Pageable pageable);
    List<TaskEntity> findTop50ByOwnerIdAndTypeOrderByCreatedAtDesc(long ownerId, String type);

    @org.springframework.data.jpa.repository.Query(
            "select t.status, count(t) from TaskEntity t where t.ownerId = :ownerId and t.historyDeletedAt is null group by t.status")
    List<Object[]> countByStatus(@org.springframework.data.repository.query.Param("ownerId") long ownerId);
    List<TaskEntity> findByStatusIn(List<String> statuses);
    int countByOwnerIdAndStatusIn(long ownerId, List<String> statuses);
    Optional<TaskEntity> findByOwnerIdAndIdempotencyKey(long ownerId, String idempotencyKey);

    /** Native writes intentionally bypass the read-only entity field so detached worker merges cannot reset it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update tasks set history_deleted_at = :now where owner_id = :ownerId and id in (:ids) "
            + "and history_deleted_at is null and status in ('SUCCEEDED','FAILED','CANCELLED')", nativeQuery = true)
    int hideHistory(@Param("ownerId") long ownerId, @Param("ids") List<String> ids, @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update tasks set history_deleted_at = :cutoff where owner_id = :ownerId "
            + "and history_deleted_at is null and status in ('SUCCEEDED','FAILED','CANCELLED') "
            + "and (finished_at is null or finished_at <= :cutoff)", nativeQuery = true)
    int clearHistory(@Param("ownerId") long ownerId, @Param("cutoff") long cutoff);

    /** Retry and deletion compete on a single database predicate; a hidden task must never be enqueued. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update tasks set status = 'QUEUED', progress = 0, stage = null, error_code = null, "
            + "message = '任务已重新排队...', output_file = null, result_json = null, attempt = attempt + 1, "
            + "started_at = null, finished_at = null, updated_at = :now "
            + "where id = :id and owner_id = :ownerId and history_deleted_at is null "
            + "and status in ('FAILED','CANCELLED') and attempt = :attempt", nativeQuery = true)
    int requeueVisible(@Param("ownerId") long ownerId, @Param("id") String id,
                       @Param("attempt") int attempt, @Param("now") long now);
}
