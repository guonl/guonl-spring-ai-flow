package com.guonl.flow.db.entity;

import lombok.Data;

/**
 * flow_run 表实体（一次流程执行轮次）。
 */
@Data
public class FlowRunDO {

    /** 运行ID */
    private String runId;

    /** 流程ID */
    private String flowId;

    /** 流程名称（冗余，便于历史查询） */
    private String flowName;

    /** 状态：RUNNING/SUCCESS/FAILED */
    private String status;

    /** 输入摘要 */
    private String inputSummary;

    /** 输入图片数 */
    private Integer inputImages;

    /** 输入表格行数 */
    private Integer inputRows;

    /** 耗时（毫秒） */
    private Long costMillis;

    /** 总token数 */
    private Integer totalTokens;

    /** 开始时间（epoch毫秒） */
    private Long startAt;

    /** 结束时间（epoch毫秒，0=未结束） */
    private Long endAt;
}
