package com.guonl.flow.core.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.core.model.NodeType;
import lombok.Data;

import java.util.List;

/**
 * 单节点运行时状态。
 */
@Data
public class NodeExecution {

    public enum Status {
        /** 等待依赖 */
        PENDING,
        /** 执行中 */
        RUNNING,
        /** 成功 */
        SUCCESS,
        /** 失败 */
        FAILED,
        /** 因上游失败被取消 */
        CANCELLED
    }

    private String nodeId;

    private String nodeName;

    private NodeType nodeType;

    private Status status = Status.PENDING;

    /** 模型原始输出 */
    private String output;

    /** JSON契约解析后的格式化输出 */
    private String parsedJson;

    /** 失败原因 */
    private String error;

    /** 实际使用模型 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 耗时（毫秒） */
    private long costMillis;

    /** 工具调用轨迹（仅工具调用节点可能非空） */
    private List<AiInvoker.ToolCallTrace> toolCalls;

    @JsonIgnore
    private long startAt;

    @JsonIgnore
    private long endAt;

    public boolean finished() {
        return status == Status.SUCCESS || status == Status.FAILED || status == Status.CANCELLED;
    }
}
