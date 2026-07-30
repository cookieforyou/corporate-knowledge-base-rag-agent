package com.enterprise.kb.domain.model;

import com.enterprise.kb.domain.enums.PromptTemplateStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Prompt 模板表
 */
@Data
@Entity
@Table(name = "kb_prompt_template")
public class KbPromptTemplate {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "version", length = 20, nullable = false)
    private String version;

    @Column(name = "template_text", columnDefinition = "TEXT", nullable = false)
    private String templateText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "JSONB")
    private String variables;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private PromptTemplateStatus status = PromptTemplateStatus.DRAFT;

    @Column(name = "ab_test_group", length = 20)
    private String abTestGroup;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
