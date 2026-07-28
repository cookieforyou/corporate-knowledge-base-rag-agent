package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbSessionRepository extends JpaRepository<KbSession, String> {

    List<KbSession> findByUserIdOrderByUpdatedAtDesc(String userId);
}
