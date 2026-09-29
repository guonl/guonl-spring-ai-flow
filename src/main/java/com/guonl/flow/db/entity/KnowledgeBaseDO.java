package com.guonl.flow.db.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * flow_knowledge 表实体（知识库元数据）。
 */
@Data
public class KnowledgeBaseDO {

    /** 知识库ID */
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 文档数（聚合查询结果，非表列） */
    private Integer docCount;

    /** 切片总数（聚合查询结果，非表列） */
    private Integer chunkCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
