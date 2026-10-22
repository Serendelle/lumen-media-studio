package com.houqing.tools.video_compressor_backend.media;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Persisted task: survives restarts, carries owner for isolation, and tracks the unified lifecycle. */
@Entity
@Table(name = "tasks")
@Getter
@Setter
public class TaskEntity {
    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(nullable = false)
    private String type;

    /** QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int progress;

    private String stage;
    private String message;

    @Column(name = "params_json", nullable = false)
    private String paramsJson;

    @Column(name = "input_files", nullable = false)
    private String inputFiles;

    @Column(name = "output_file")
    private String outputFile;

    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "error_code")
    private String errorCode;

    @Column(nullable = false)
    private int attempt = 1;

    /** PRO or AI — which mode created it. */
    @Column(nullable = false)
    private String origin = "PRO";

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    /** Personal history visibility only. A late worker save must never undo an explicit history deletion. */
    @Column(name = "history_deleted_at", insertable = false, updatable = false)
    private Long historyDeletedAt;
}
