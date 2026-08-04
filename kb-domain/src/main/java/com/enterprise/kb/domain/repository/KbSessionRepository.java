package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface KbSessionRepository extends JpaRepository<KbSession, String> {

    List<KbSession> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 消息计数原子自增（3.1 会话归档）—— 避免读-改-写在虚拟线程并发归档下丢计数
     */
    @Modifying
    @Transactional
    @Query("update KbSession s set s.messageCount = s.messageCount + :delta, s.updatedAt = CURRENT_TIMESTAMP where s.id = :id")
    int incrementMessageCount(@Param("id") String id, @Param("delta") int delta);
}
