package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbChunkRepository extends JpaRepository<KbChunk, String> {

    List<KbChunk> findByDocIdOrderByChunkIndex(String docId);

    List<KbChunk> findByVectorIdIn(List<String> vectorIds);
}
