package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KbPromptTemplateRepository extends JpaRepository<KbPromptTemplate, String> {

    Optional<KbPromptTemplate> findByNameAndVersion(String name, String version);
}
