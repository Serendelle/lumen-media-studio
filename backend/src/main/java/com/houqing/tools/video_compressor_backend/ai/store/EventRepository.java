package com.houqing.tools.video_compressor_backend.ai.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<AiEntities.Event, Long> {
    List<AiEntities.Event> findTop500ByConversationIdAndSeqGreaterThanOrderBySeqAsc(String conversationId, long afterSeq);
}
