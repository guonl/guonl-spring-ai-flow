package com.guonl.flow.db.entity;

import lombok.Data;

/**
 * flow_node_execution 表实体（单节点执行记录）。
 */
@Data
public class FlowNodeExecutionDO {

    /** 自增主键 */
    private Long id;

    /** 运行ID */
    private String runId;

    /** 节点序号（同一次运行内的拓扑序） */
    private Integer seq;

    /** 节点ID */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型：TEXT/IMAGE/EXCEL/JSON/COMBINED */
    private String nodeType;

    /** 状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 模型原始输出 */
    private String output;

    /** JSON解析结果（type=json 节点） */
    private String parsedJson;

    /** 错误信息 */
    private String error;

    /** 使用的模型 */
    private String model;

    /** 提示词token数 */
    private Integer promptTokens;

    /** 补全token数 */
    private Integer completionTokens;

    /** 总token数 */
    private Integer totalTokens;

    /** 耗时（毫秒） */
    private Long costMillis;

    /** 工具调用轨迹JSON（仅工具调用节点，序列化的ToolCallTrace列表） */
    private String toolCalls;

    /** 开始时间（epoch毫秒） */
    private Long startAt;

    /** 结束时间（epoch毫秒，0=未结束） */
    private Long endAt;
}
