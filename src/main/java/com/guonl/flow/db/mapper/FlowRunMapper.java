package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.FlowRunDO;
import com.guonl.flow.db.entity.RunStatsDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * flow_run 表 Mapper（执行轮次）。
 */
public interface FlowRunMapper {

    int insert(FlowRunDO record);

    /** 运行结束：更新状态/耗时/结束时间/总token */
    int updateFinish(FlowRunDO record);

    FlowRunDO findById(@Param("runId") String runId);

    /** 历史列表（按开始时间倒序） */
    List<FlowRunDO> findAll();

    /** 条件分页检索（运行ID/流程模糊、状态精确、开始时间范围[毫秒]，按开始时间倒序） */
    List<FlowRunDO> searchPage(@Param("runId") String runId,
                               @Param("flow") String flow,
                               @Param("status") String status,
                               @Param("startFromMs") Long startFromMs,
                               @Param("startToMs") Long startToMs,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    /** 条件计数（与 searchPage 同条件） */
    long countSearch(@Param("runId") String runId,
                     @Param("flow") String flow,
                     @Param("status") String status,
                     @Param("startFromMs") Long startFromMs,
                     @Param("startToMs") Long startToMs);

    RunStatsDO selectStats();
}
