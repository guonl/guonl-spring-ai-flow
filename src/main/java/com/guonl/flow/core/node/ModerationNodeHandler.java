package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容审核节点：审核文本（变量解析后的指令；未用变量时自动带上流程输入与上游输出）送
 * 审核模型（Spring AI {@link ModerationModel} SPI），输出 {@code json={flagged, categories}}，
 * 下游条件路由节点可按 {@code {{mod1.json.flagged}}} 分流降级。
 * <p>不走会话大模型链路，实现 {@link NodeHandler} 而非继承 AbstractNodeHandler。</p>
 */
@Component
public class ModerationNodeHandler implements NodeHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<ModerationModel> moderationModelProvider;

    public ModerationNodeHandler(ObjectProvider<ModerationModel> moderationModelProvider) {
        this.moderationModelProvider = moderationModelProvider;
    }

    @Override
    public NodeType supports() {
        return NodeType.MODERATION;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) {
        // 1. 变量表：流程输入 + 全部祖先产出
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String ancestorId : ctx.getAncestorIds()) {
            VarResolver.registerNode(vars, ancestorId, ctx.getUpstreamOutputs().get(ancestorId));
        }
        String template = node.getPrompt() == null ? "" : node.getPrompt();
        VarResolver.ResolveResult resolved = VarResolver.resolve(template, vars);
        String text = resolved.resolved().trim();
        // 未使用变量时自动追加待审核内容（流程输入 + 上游输出），保证零模板成本
        if (!resolved.usedVars()) {
            String context = buildAutoContext(ctx);
            if (!context.isEmpty()) {
                text = (text + "\n\n【待审核内容】\n" + context).trim();
            }
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("节点「" + node.getName()
                + "」没有可审核的内容：请配置审核指令（可用变量引用上游输出）或连接上游节点");
        }
        // 2. 调用审核模型
        ModerationModel model = moderationModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("内容审核模型未装配（ModerationModel）。"
                + "mock模式自动可用；llm模式需启用 spring.ai.model.moderation=openai 并配置审核端点");
        }
        ModerationResponse response = model.call(new ModerationPrompt(text));
        if (response.getResult() == null || response.getResult().getOutput() == null
            || response.getResult().getOutput().getResults() == null
            || response.getResult().getOutput().getResults().isEmpty()) {
            throw new IllegalStateException("内容审核模型未返回结果");
        }
        ModerationResult result = response.getResult().getOutput().getResults().get(0);
        boolean flagged = result.isFlagged();
        List<String> categories = extractFlaggedCategories(result);
        // 3. 输出：text=人类可读结论；json={flagged, categories} 供下游路由分流
        String summary = flagged
            ? "⚠️ 命中违规类别：" + String.join("、", categories)
            : "✅ 审核通过，未命中违规类别";
        ObjectNode json = MAPPER.createObjectNode();
        json.put("flagged", flagged);
        ArrayNode cats = json.putArray("categories");
        categories.forEach(cats::add);
        NodeOutput output = new NodeOutput(summary, json);
        output.setToolCalls(List.of(new AiInvoker.ToolCallTrace("内容审核", truncate(text, 500), summary)));
        return output;
    }

    /** 从Categories布尔标记中收集命中的类别名（Jackson按isXxx序列化，取值为true的字段） */
    private List<String> extractFlaggedCategories(ModerationResult result) {
        List<String> categories = new ArrayList<>();
        if (result.getCategories() == null) {
            return categories;
        }
        JsonNode tree = MAPPER.valueToTree(result.getCategories());
        tree.fieldNames().forEachRemaining(field -> {
            if (tree.get(field).asBoolean(false)) {
                categories.add(field);
            }
        });
        return categories;
    }

    /** 未使用变量时自动追加的待审核内容（与基类自动上下文同构） */
    private String buildAutoContext(NodeContext ctx) {
        List<String> lines = new ArrayList<>();
        if (ctx.getFlowInput() != null && ctx.getFlowInput().hasText()) {
            lines.add("- 流程输入数据：" + truncate(ctx.getFlowInput().getText().trim(), 4000));
        }
        for (String ancestorId : ctx.getAncestorIds()) {
            NodeOutput output = ctx.getUpstreamOutputs().get(ancestorId);
            if (output == null || output.getText() == null) {
                continue;
            }
            String name = ctx.getUpstreamNames().getOrDefault(ancestorId, ancestorId);
            lines.add("- 上游节点「" + name + "」输出：" + truncate(output.getText(), 6000));
        }
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（已截断，共" + text.length() + "字符）";
    }
}
