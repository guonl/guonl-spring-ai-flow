package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.KnowledgeDocDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_knowledge_doc 表 Mapper（知识库文档）。
 */
public interface KnowledgeDocMapper {

    int insert(KnowledgeDocDO record);

    KnowledgeDocDO findById(@Param("id") Long id);

    List<KnowledgeDocDO> findByKbId(@Param("kbId") Long kbId);

    /** 更新ETL进度（已向量化切片数） */
    int updateProcessed(@Param("id") Long id, @Param("processed") Integer processed);

    /** 结束ETL：置终态（READY/FAILED），失败时带原因 */
    int finishEtl(@Param("id") Long id, @Param("status") String status, @Param("errorMsg") String errorMsg);

    /** 统计知识库下处于处理中的文档数 */
    int countProcessingByKbId(@Param("kbId") Long kbId);

    /** 应用重启恢复：将全部PROCESSING文档置为FAILED */
    int failAllProcessing(@Param("errorMsg") String errorMsg);

    int deleteById(@Param("id") Long id);

    int deleteByKbId(@Param("kbId") Long kbId);
}
