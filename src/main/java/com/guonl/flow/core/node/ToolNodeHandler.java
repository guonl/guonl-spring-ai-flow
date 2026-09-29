package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

/**
 * 工具调用节点：挂载内置工具集（时间/计算/网页抓取/JSON提取），
 * 模型按需自主调用工具获取真实数据后作答。
 */
@Component
public class ToolNodeHandler extends AbstractNodeHandler {

    public ToolNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.TOOL;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你可以使用提供的工具（获取当前时间、数学计算、网页抓取、JSON提取）获取真实数据，"
            + "在需要这些信息时自主调用相应工具，并基于工具返回的结果作答，"
            + "不要凭空编造数据。";
    }
}
