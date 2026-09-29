package com.guonl.flow.db.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * flow_knowledge_doc 表实体（知识库文档）。
 */
@Data
public class KnowledgeDocDO {

    /** 文档ID */
    private Long id;

    /** 所属知识库ID */
    private Long kbId;

    /** 文档名称 */
    private String docName;

    /** 切片数 */
    private Integer chunkCount;

    /** 切片总数（ETL进度基数） */
    private Integer totalChunks;

    /** 已向量化切片数（ETL进度） */
    private Integer processedChunks;

    /** 向量库切片ID列表（JSON数组字符串） */
    private String chunkIds;

    /** 状态：PROCESSING/READY/FAILED */
    private String status;

    /** 失败原因（FAILED时） */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
