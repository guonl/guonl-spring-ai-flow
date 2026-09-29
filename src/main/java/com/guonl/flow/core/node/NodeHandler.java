package com.guonl.flow.core.node;

import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;

/**
 * 节点处理器SPI：一种 {@link NodeType} 一个实现，
 * 负责「组装提示词与素材 → 调用模型 → 解析产出」的完整链路。
 */
public interface NodeHandler {

    /** 本处理器支持的节点类型 */
    NodeType supports();

    /**
     * 执行节点
     *
     * @param node   节点定义
     * @param ctx    执行上下文（流程输入 + 上游产出）
     * @return 节点产出
     * @throws Exception 执行失败时抛出，由引擎记录为节点失败
     */
    NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception;
}
