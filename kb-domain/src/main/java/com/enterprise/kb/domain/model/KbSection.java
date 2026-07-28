package com.enterprise.kb.domain.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档章节表
 */
@Data
@Entity
@Table(name = "kb_section")
public class KbSection {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "doc_id", length = 36, nullable = false)
    private String docId;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "level")
    private Integer level = 1;

    @Column(name = "order_index")
    private Integer orderIndex;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;
}
