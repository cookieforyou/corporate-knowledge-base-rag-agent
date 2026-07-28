package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbSectionRepository extends JpaRepository<KbSection, String> {

    List<KbSection> findByDocIdOrderByOrderIndex(String docId);
}
