package com.houqing.tools.video_compressor_backend.spec;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

public final class CollectionEntities {
    private CollectionEntities() {}

    @Entity @Table(name = "spec_collections") @Getter @Setter
    public static class CollectionLink {
        @Id private String id;
        @Column(name = "project_id", nullable = false) private String projectId;
        @Column(name = "token_hash", nullable = false) private String tokenHash;
        @Column(nullable = false) private int enabled;
        @Column(name = "expires_at", nullable = false) private long expiresAt;
        @Column(name = "created_at", nullable = false) private long createdAt;
        @Column(name = "used_files", nullable = false) private int usedFiles;
        @Column(name = "used_bytes", nullable = false) private long usedBytes;
    }

    @Entity @Table(name = "spec_submissions") @Getter @Setter
    public static class Submission {
        @Id private String id;
        @Column(name = "collection_id", nullable = false) private String collectionId;
        @Column(name = "request_key", nullable = false) private String requestKey;
        @Column(nullable = false) private String filename;
        @Column(name = "submitted_by", nullable = false) private String submittedBy;
        @Column(nullable = false) private String identifier;
        @Column(name = "created_at", nullable = false) private long createdAt;
    }
}
