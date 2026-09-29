package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.FlowNodeExecutionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_node_execution 表 Mapper（节点执行记录）。
 */
public interface FlowNodeExecutionMapper {

    int insertBatch(@Param("nodes") List<FlowNodeExecutionDO> nodes);

    /** 节点状态写穿更新（run_id + node_id 定位，动态更新非空字段） */
    int updateState(FlowNodeExecutionDO record);

    /** 按运行ID查询（seq 升序） */
    List<FlowNodeExecutionDO> findByRunId(@Param("runId") String runId);

    /** 按运行ID批量查询（用于历史列表装配 nodes） */
    List<FlowNodeExecutionDO> findByRunIds(@Param("runIds") List<String> runIds);
}
