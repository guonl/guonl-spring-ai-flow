package com.guonl.flow.db.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * flow_definition 表实体（流程定义）。
 */
@Data
public class FlowDefinitionDO {

    /** 流程ID */
    private String id;

    /** 流程名称 */
    private String name;

    /** 流程描述 */
    private String description;

    /** 节点定义数组（JSON字符串） */
    private String nodesJson;

    /** 连线定义数组（JSON字符串） */
    private String edgesJson;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
