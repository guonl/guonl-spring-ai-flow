package com.guonl.flow.core.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次流程运行：全部节点的运行时状态与整体统计。
 */
@Data
public class FlowExecution {

    public enum Status {
        /** 执行中 */
        RUNNING,
        /** 全部成功 */
        SUCCESS,
        /** 存在失败节点（下游已取消） */
        FAILED
    }

    private String runId;

    private String flowId;

    private String flowName;

    private Status status = Status.RUNNING;

    /** 输入摘要（文本截断，供历史记录展示） */
    private String inputSummary;

    /** 输入携带的图片数 */
    private int inputImages;

    /** 输入携带的Excel行数 */
    private int inputRows;

    private long startAt;

    private long endAt;

    /** 节点执行状态：nodeId -> 执行状态 */
    private Map<String, NodeExecution> nodes = new LinkedHashMap<>();

    @JsonIgnore
    private FlowInput input;

    public long getCostMillis() {
        return endAt > 0 ? endAt - startAt : System.currentTimeMillis() - startAt;
    }

    /** is-getter 命名：Jackson 序列化为 finished 字段，前端据此停止轮询 */
    public boolean isFinished() {
        return status != Status.RUNNING;
    }

    public int getSuccessCount() {
        return (int) nodes.values().stream().filter(n -> n.getStatus() == NodeExecution.Status.SUCCESS).count();
    }

    public int getTotalTokens() {
        return nodes.values().stream()
            .map(NodeExecution::getTotalTokens)
            .filter(t -> t != null && t > 0)
            .reduce(0, Integer::sum);
    }
}
