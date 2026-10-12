package com.houqing.tools.video_compressor_backend.ai.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** JPA entities for AI conversations (kept together: they are only used by the ai package). */
public final class AiEntities {
    private AiEntities() {}

    @Entity
    @Table(name = "ai_conversations")
    @Getter
    @Setter
    public static class Conversation {
        @Id
        private String id;
        @Column(name = "owner_id", nullable = false)
        private long ownerId;
        @Column(nullable = false)
        private String title;
        /** see ConversationState */
        @Column(nullable = false)
        private String state;
        @Column(name = "plan_json")
        private String planJson;
        @Column(name = "plan_version", nullable = false)
        private int planVersion;
        /** requirements, attached file ids, step results, review */
        @Column(name = "context_json")
        private String contextJson;
        @Column(name = "prompt_version", nullable = false)
        private String promptVersion;
        @Column(name = "created_at", nullable = false)
        private long createdAt;
        @Column(name = "updated_at", nullable = false)
        private long updatedAt;
    }

    @Entity
    @Table(name = "ai_messages")
    @Getter
    @Setter
    public static class Message {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "conversation_id", nullable = false)
        private String conversationId;
        /** user | assistant */
        @Column(nullable = false)
        private String role;
        private String agent;
        @Column(nullable = false)
        private String content;
        @Column(name = "created_at", nullable = false)
        private long createdAt;
    }

    @Entity
    @Table(name = "ai_events")
    @Getter
    @Setter
    public static class Event {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long seq;
        @Column(name = "conversation_id", nullable = false)
        private String conversationId;
        @Column(nullable = false)
        private String type;
        @Column(name = "payload_json", nullable = false)
        private String payloadJson;
        @Column(name = "created_at", nullable = false)
        private long createdAt;
    }
}
