package com.houqing.tools.video_compressor_backend.spec;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<CollectionEntities.Submission, String> {
    Optional<CollectionEntities.Submission> findByCollectionIdAndRequestKey(String collectionId, String requestKey);
}
