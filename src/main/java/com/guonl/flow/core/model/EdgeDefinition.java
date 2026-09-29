package com.guonl.flow.core.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程连线：from -> to，构成DAG编排关系。
 * 下游节点在其全部上游依赖成功后才会执行（多上游即汇流点，天然支持并行分支）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EdgeDefinition {

    /** 上游节点ID（兼容历史数据的 source 别名） */
    @JsonAlias("source")
    private String from;

    /** 下游节点ID（兼容历史数据的 target 别名） */
    @JsonAlias("target")
    private String to;

    /** 条件分支名（仅 from 为 ROUTER 节点时使用；空=默认边，仅当没有任何条件边命中时激活） */
    private String condition;
}
