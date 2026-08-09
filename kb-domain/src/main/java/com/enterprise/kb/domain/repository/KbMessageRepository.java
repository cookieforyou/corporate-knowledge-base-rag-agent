package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbMessageRepository extends JpaRepository<KbMessage, String> {

    List<KbMessage> findBySessionIdOrderByCreatedAt(String sessionId);

    /** 记忆回填（历史会话续聊）：最近 N 条倒序取，调用方反转为升序写入记忆窗口 */
    List<KbMessage> findTop20BySessionIdOrderByCreatedAtDesc(String sessionId);
}
