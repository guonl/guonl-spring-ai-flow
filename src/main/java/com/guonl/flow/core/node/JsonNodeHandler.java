package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JSON处理节点：JSON数据输入，完成清洗、转换、校验、补全等处理并按指定格式返回。
 * 输入未通过变量引用时，自动将流程输入JSON作为待处理数据；输入必须是合法JSON。
 */
@Slf4j
@Component
public class JsonNodeHandler extends AbstractNodeHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.JSON;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是JSON数据处理器，精确理解数据结构与处理规则，"
            + "对输入JSON完成指定处理后输出结果JSON，字段值保持数据类型语义（数字不加引号），不输出任何解释。";
    }

    @Override
    protected String buildAutoContext(com.guonl.flow.core.node.NodeContext ctx) {
        FlowInput input = ctx.getFlowInput();
        // JSON节点要求输入必须是合法JSON，提前校验给出可理解的错误
        if (ctx.getAncestorIds().isEmpty() && input != null && input.hasText()) {
            try {
                JsonNode ignored = MAPPER.readTree(input.getText());
            } catch (Exception e) {
                throw new IllegalArgumentException("JSON节点的流程输入不是合法JSON：" + e.getMessage());
            }
        }
        return super.buildAutoContext(ctx);
    }
}
