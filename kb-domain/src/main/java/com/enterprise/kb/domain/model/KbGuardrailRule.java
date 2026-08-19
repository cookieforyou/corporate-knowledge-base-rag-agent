package com.enterprise.kb.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护栏词项表（v2.53 词表 DB 单轨，设计 12.7 词表工程）——Plan C 修订形态
 * （v2.52 钉死复审推荐：DB 单轨 + git 导出存档，否决双轨）的唯一事实源。
 *
 * <p><b>编码态纪律</b>（第七节条 2）：{@code valueB64} 恒为逐条 Base64 编码态
 * 落库，加载层（kb-domain DbGuardrailRulesSource）解码消费；运营 API 契约
 * 只收编码态（前端编码后上送），网络 / 日志 / 审计全程不承载明文。
 *
 * <p><b>去重键</b>：{@code (side, type, fingerprint)} 唯一约束——指纹为
 * SHA-256(规范化后解码值)（KEYWORD 小写化后 / REGEX 原样），与加载层
 * 运行时语义一致，对齐带外通道 import_words.py 的幂等去重口径。
 */
@Data
@Entity
@Table(name = "kb_guardrail_rule")
public class KbGuardrailRule {

    /** 词项业务 id（运营 / 审计 / 清单引用锚点；API 新建自动生成 api-inj-/api-out- 前缀） */
    @Id
    @Column(name = "id", length = 50)
    private String id;

    /** 侧别：injection | output */
    @Column(name = "side", length = 10, nullable = false)
    private String side;

    /** 族系枚举名（注入侧七分法 GuardrailFamily / 输出侧三分类 OutputFamily，中性命名） */
    @Column(name = "family", length = 40, nullable = false)
    private String family;

    /** 语种标注（zh / en / ja / …，可空串） */
    @Column(name = "lang", length = 10, nullable = false)
    private String lang = "";

    /** 匹配类型：KEYWORD（大小写不敏感子串）| REGEX（结构模式，CASE_INSENSITIVE 编译） */
    @Column(name = "type", length = 10, nullable = false)
    private String type;

    /** 词值（逐条 Base64 编码态，加载层解码；KEYWORD 存小写规范化后重编码） */
    @Column(name = "value_b64", columnDefinition = "TEXT", nullable = false)
    private String valueB64;

    /** SHA-256(规范化后解码值) 十六进制全量——去重键成分 */
    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    /** 动作档：BLOCK（命中即拒）| FLAG（观察档：计数+审计标记不拒绝）；新建默认 FLAG（A4 生命周期） */
    @Column(name = "action", length = 10, nullable = false)
    private String action = "FLAG";

    /** 停用开关（运营期热停用单条） */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 词项来源：MIGRATION（存量迁移）| API（运营端点）| IMPORT（带外通道预留） */
    @Column(name = "origin", length = 20, nullable = false)
    private String origin = "API";

    /** 创建者（JWT 身份，运营审计锚点） */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    /** 最近修改者（JWT 身份） */
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
