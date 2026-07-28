package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbFeedbackRepository extends JpaRepository<KbFeedback, String> {

    List<KbFeedback> findByMessageId(String messageId);
}
