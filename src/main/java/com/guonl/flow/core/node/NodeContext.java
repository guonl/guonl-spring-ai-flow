package com.guonl.flow.core.node;

import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.NodeOutput;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 节点执行上下文：由引擎构建，携带流程输入与全部祖先节点的产出。
 */
@Getter
@RequiredArgsConstructor
public class NodeContext {

    /** 流程业务输入 */
    private final FlowInput flowInput;

    /** 祖先节点产出：nodeId -> 产出（按拓扑序） */
    private final Map<String, NodeOutput> upstreamOutputs;

    /** 祖先节点名称：nodeId -> 名称（组装上下文提示用） */
    private final Map<String, String> upstreamNames;

    /** 本节点全部祖先ID（拓扑序） */
    private final List<String> ancestorIds;

    /** 直接上游节点ID（仅有边直连本节点的父节点，按流程定义顺序）；聚合节点按此合并产出 */
    private final List<String> parentIds;

    /** 流式增量回调：节点输出逐片段推送（SSE），非流式场景为 null */
    private final java.util.function.Consumer<String> streamSink;

    /** 会话ID（多轮记忆隔离键，来自流程输入；空=无记忆） */
    private final String conversationId;
}
