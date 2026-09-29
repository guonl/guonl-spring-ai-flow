package com.guonl.flow.core.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程定义：业务场景编排的持久化单元。
 * 节点+连线构成DAG，支持串行、并行、汇流等任意编排形态。
 */
@Data
public class FlowDefinition {

    /** 流程ID */
    private String id;

    /** 流程名称 */
    private String name;

    /** 流程描述 */
    private String description;

    /** 节点列表 */
    private List<NodeDefinition> nodes = new ArrayList<>();

    /** 连线列表 */
    private List<EdgeDefinition> edges = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public NodeDefinition findNode(String nodeId) {
        return nodes.stream().filter(n -> n.getId().equals(nodeId)).findFirst().orElse(null);
    }
}
