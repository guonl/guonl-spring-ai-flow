package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

/**
 * 文本理解节点：纯文字输入，理解需求并按指定格式返回。
 */
@Component
public class TextNodeHandler extends AbstractNodeHandler {

    public TextNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.TEXT;
    }

    @Override
    protected String resolveSystemPrompt(com.guonl.flow.core.model.NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是文本理解与信息提取专家，准确理解输入内容的意图与要素，"
            + "严格按照要求输出结果，不输出任何多余解释。";
    }
}
