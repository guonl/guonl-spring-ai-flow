package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

/**
 * 组合处理节点：文本+图片+JSON+表格等多源组合输入，综合处理并按指定格式返回。
 * 自动携带全部可用素材（图片）与上下文数据（文本/JSON/Excel/上游产出）。
 */
@Component
public class CombinedNodeHandler extends AbstractNodeHandler {

    public CombinedNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.COMBINED;
    }

    @Override
    protected String resolveSystemPrompt(com.guonl.flow.core.model.NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是多源数据综合处理引擎，擅长融合文本、图片、JSON、表格等多种输入进行交叉分析与处理，"
            + "分清各数据来源的边界，只依据给定数据作答，严格按照要求输出结果。";
    }
}
