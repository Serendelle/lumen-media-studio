package com.houqing.tools.video_compressor_backend.media;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A stored file (uploaded input or produced output) owned by one user. The id is the only handle a client ever sees. */
@Entity
@Table(name = "media_files")
@Getter
@Setter
public class MediaFile {
    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    /** Name on disk inside the storage root; never derived from client input. */
    @Column(name = "stored_name", nullable = false, unique = true)
    private String storedName;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    /** INPUT or OUTPUT */
    @Column(nullable = false)
    private String role;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "probe_json")
    private String probeJson;

    @Column(name = "source_task_id")
    private String sourceTaskId;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    public MediaKind kind() { return MediaKind.valueOf(mediaType); }
}
