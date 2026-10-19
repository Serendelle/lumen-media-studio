package com.houqing.tools.video_compressor_backend.lesson;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One analysed lesson video: the validated outline + snippets, and the raw transcript / OCR they came from. */
@Entity
@Table(name = "lessons")
@Getter
@Setter
public class LessonEntity {
    @Id
    private String id;
    @Column(name = "owner_id", nullable = false)
    private long ownerId;
    @Column(name = "video_file_id", nullable = false)
    private String videoFileId;
    @Column(name = "task_id")
    private String taskId;
    @Column(nullable = false)
    private String title;
    /** validated {title, summary, duration, nodes[], snippets[]} */
    @Column(name = "data_json", nullable = false)
    private String dataJson;
    /** [{begin, end, text}] in seconds */
    @Column(name = "transcript_json", nullable = false)
    private String transcriptJson;
    /** [{t, text}] screen text per changed frame, kept so every snippet can be checked against it */
    @Column(name = "ocr_json")
    private String ocrJson;
    /** {asrModel, llmModel, promptVersion, warnings[]} */
    @Column(name = "meta_json")
    private String metaJson;
    @Column(name = "created_at", nullable = false)
    private long createdAt;
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;
}
