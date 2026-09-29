package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.node.NodeContext;
import org.springframework.stereotype.Component;

/**
 * Excel处理节点：表格数据输入（运行时上传Excel解析为行JSON），
 * 完成筛选、汇总、校验、分类等处理并按指定格式返回。
 */
@Component
public class ExcelNodeHandler extends AbstractNodeHandler {

    public ExcelNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.EXCEL;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是表格数据分析引擎，逐行严谨处理Excel数据，"
            + "只依据给定数据作答，不编造数据，严格按照要求输出结果。";
    }

    @Override
    protected String buildAutoContext(NodeContext ctx) {
        FlowInput input = ctx.getFlowInput();
        boolean hasUpstreamRows = ctx.getAncestorIds().stream()
            .anyMatch(id -> ctx.getUpstreamOutputs().containsKey(id));
        // Excel节点必须有表格数据来源：运行时上传 或 上游产出
        if ((input == null || !input.hasExcel()) && !hasUpstreamRows && ctx.getAncestorIds().isEmpty()) {
            throw new IllegalArgumentException("Excel处理节点缺少表格数据，请在运行时上传Excel文件");
        }
        return super.buildAutoContext(ctx);
    }
}
