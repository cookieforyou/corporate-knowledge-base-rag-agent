package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, String> {

    List<KbDocument> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * 增量重入库原子占用（簇⑥ C1）：仅 SUCCESS/FAILED 态可被占用为 REINDEXING，
     * 返回影响行数——0 = 状态已被并发占用或不可重入库（调用方据此返回 DOC_NOT_READY）。
     *
     * <p>DB 级 check-then-act 原子化：单条 UPDATE ... WHERE status IN (...) 消除
     * 双请求竞态双占用，零 Redis 依赖（与 3.17 SETNX 守卫互补的另一种守卫形态）。
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE KbDocument d SET d.status = :reindexing "
        + "WHERE d.id = :id AND d.status IN :acquirable")
    int acquireForReindex(@Param("id") String id,
                          @Param("reindexing") DocumentStatus reindexing,
                          @Param("acquirable") List<DocumentStatus> acquirable);
}
