package com.houqing.tools.video_compressor_backend.ai.store;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<AiEntities.Conversation, String> {
    Optional<AiEntities.Conversation> findByIdAndOwnerId(String id, long ownerId);
    Page<AiEntities.Conversation> findByOwnerId(long ownerId, Pageable pageable);
    List<AiEntities.Conversation> findByStateIn(List<String> states);
    int countByOwnerIdAndStateIn(long ownerId, List<String> states);
}
