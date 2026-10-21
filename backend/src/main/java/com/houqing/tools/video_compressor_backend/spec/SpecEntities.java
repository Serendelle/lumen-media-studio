package com.houqing.tools.video_compressor_backend.spec;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
/** Persistence for the material spec desk: one project (spec + roster + templates) and its attached files. */
public final class SpecEntities {
    private SpecEntities() {}

    @Entity
    @Table(name = "spec_projects")
    @Getter
    @Setter
    public static class SpecProject {
        @Id
        private String id;
        @Column(name = "owner_id", nullable = false)
        private long ownerId;
        @Column(nullable = false)
        private String name;
        @Column(name = "notice_text")
        private String noticeText;
        /** The confirmed material spec: slots, rules (with the notice sentence each came from), naming pattern. */
        @Column(name = "spec_json", nullable = false)
        private String specJson;
        /** {fields[], nameField, idField?, people[{key, row, values{}}]} */
        @Column(name = "roster_json")
        private String rosterJson;
        @Column(name = "docx_template_file_id")
        private String docxTemplateFileId;
        @Column(name = "xlsx_template_file_id")
        private String xlsxTemplateFileId;
        @Column(name = "xlsx_header_row")
        private Integer xlsxHeaderRow;
        /** Optimistic concurrency for spec edits. */
        @Column(nullable = false)
        private int version;
        @Column(name = "created_at", nullable = false)
        private long createdAt;
        @Column(name = "updated_at", nullable = false)
        private long updatedAt;
    }

    @Entity
    @Table(name = "spec_items")
    @Getter
    @Setter
    public static class SpecItem {
        @Id
        private String id;
        @Column(name = "project_id", nullable = false)
        private String projectId;
        @Column(name = "owner_id", nullable = false)
        private long ownerId;
        @Column(name = "file_id", nullable = false)
        private String fileId;
        /** Path inside the dropped folder (or the bare file name); the matching evidence. */
        @Column(name = "relative_path", nullable = false)
        private String relativePath;
        @Column(name = "person_key")
        private String personKey;
        @Column(name = "slot_key")
        private String slotKey;
        /** AUTO | MANUAL | SUBMISSION | AI | NONE */
        @Column(name = "match_method", nullable = false)
        private String matchMethod;
        @Column(name = "match_note")
        private String matchNote;
        @Column(name = "submitted_by")
        private String submittedBy;
        @Column(name = "submitted_identifier")
        private String submittedIdentifier;
        @Column(name = "submission_id")
        private String submissionId;
        @Column(name = "form_row_id")
        private String formRowId;
        /** UNMATCHED | OK | FIXABLE | INVALID | FIXING | FIXED | FIX_FAILED | DUPLICATE */
        @Column(nullable = false)
        private String status;
        @Column(name = "check_json")
        private String checkJson;
        @Column(name = "fixed_file_id")
        private String fixedFileId;
        @Column(name = "fix_task_id")
        private String fixTaskId;
        @Column(name = "created_at", nullable = false)
        private long createdAt;
        @Column(name = "updated_at", nullable = false)
        private long updatedAt;
    }
}
