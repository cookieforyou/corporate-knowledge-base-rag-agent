package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbMessageRepository extends JpaRepository<KbMessage, String> {

    List<KbMessage> findBySessionIdOrderByCreatedAt(String sessionId);
}
