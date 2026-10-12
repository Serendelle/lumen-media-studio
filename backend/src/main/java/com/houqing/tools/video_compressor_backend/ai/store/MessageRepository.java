package com.houqing.tools.video_compressor_backend.ai.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<AiEntities.Message, Long> {
    List<AiEntities.Message> findByConversationIdOrderByIdAsc(String conversationId);
}
