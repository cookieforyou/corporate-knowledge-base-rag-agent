package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, String> {

    List<KbDocument> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
