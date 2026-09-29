package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.KnowledgeBaseDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_knowledge 表 Mapper（知识库 CRUD）。
 */
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBaseDO record);

    KnowledgeBaseDO findById(@Param("id") Long id);

    /** 列表（LEFT JOIN 文档表聚合 doc_count/chunk_count） */
    List<KnowledgeBaseDO> findAll();

    int deleteById(@Param("id") Long id);
}
