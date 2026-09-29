package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.FlowDefinitionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_definition 表 Mapper（流程定义 CRUD）。
 */
public interface FlowDefinitionMapper {

    int insert(FlowDefinitionDO record);

    int update(FlowDefinitionDO record);

    int deleteById(@Param("id") String id);

    FlowDefinitionDO findById(@Param("id") String id);

    List<FlowDefinitionDO> findAll();

    /** 关键词分页检索（name/description 模糊匹配，按更新时间倒序；keyword 空则全量） */
    List<FlowDefinitionDO> searchPage(@Param("keyword") String keyword,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /** 关键词计数（与 searchPage 同条件） */
    long countSearch(@Param("keyword") String keyword);
}
