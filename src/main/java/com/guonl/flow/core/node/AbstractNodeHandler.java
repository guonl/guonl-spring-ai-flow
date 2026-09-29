package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.ai.JsonExtractor;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点处理器基类：固化「变量解析 → 自动上下文 → 输出契约 → 模型调用 → 结果解析」流水线。
 * <p>子类只需声明类型并按需提供多模态素材等差异化能力。</p>
 */
@Slf4j
public abstract class AbstractNodeHandler implements NodeHandler {

    protected final AiInvoker aiInvoker;
    protected final AiProperties aiProperties;

    protected AbstractNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        this.aiInvoker = aiInvoker;
        this.aiProperties = aiProperties;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception {
        if (node.getPrompt() == null || node.getPrompt().isBlank()) {
            throw new IllegalArgumentException("节点「" + node.getName() + "」未配置处理指令");
        }
        // 1. 变量表：流程输入 + 全部祖先产出
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String ancestorId : ctx.getAncestorIds()) {
            VarResolver.registerNode(vars, ancestorId, ctx.getUpstreamOutputs().get(ancestorId));
        }
        // 2. 变量解析；未使用变量时自动追加上下文数据，保证零模板成本
        VarResolver.ResolveResult resolved = VarResolver.resolve(node.getPrompt(), vars);
        String userPrompt = resolved.resolved();
        if (resolved.missingVars() > 0) {
            log.warn("[Node] {} unresolved vars={}, node={}", node.getId(), resolved.missingVars(), node.getName());
        }
        if (!resolved.usedVars()) {
            userPrompt = userPrompt + buildAutoContext(ctx);
        }
        // 3. 输出契约（纠错重试期间保持不变）
        String outputRequirement = buildOutputRequirement(node);
        // 4. 多模态素材
        List<AiInvoker.MediaItem> medias = collectMedias(node, ctx.getFlowInput());
        // 5. 调用+解析循环：解析/校验失败时把错误反馈给模型自纠
        int maxAttempts = 1 + Math.max(0, aiProperties.getOutputRetryAttempts());
        String correction = null;
        IllegalStateException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String prompt = userPrompt + "\n\n" + outputRequirement;
            if (correction != null) {
                prompt = prompt + "\n\n" + correction;
            }
            AiInvoker.InvokeResult result = aiInvoker.invoke(AiInvoker.InvokeRequest.builder()
                .bizKey(node.getId() + ":" + node.getName())
                .systemPrompt(resolveSystemPrompt(node))
                .userPrompt(prompt)
                .medias(medias)
                .model(node.getModel())
                .temperature(node.getTemperature())
                .onDelta(ctx.getStreamSink())
                .useTools(node.getType() == NodeType.TOOL)
                .conversationId(ctx.getFlowInput() != null ? ctx.getFlowInput().getConversationId() : null)
                .memoryTurns(node.getMemoryTurns())
                .build());
            try {
                NodeOutput output = parseOutput(node, result.getContent());
                output.setToolCalls(result.getToolCalls());
                output.setUsage(NodeOutput.Usage.of(result));
                return output;
            } catch (IllegalStateException e) {
                lastError = e;
                log.warn("[Node] {} output invalid (attempt {}/{}): {}", node.getId(), attempt, maxAttempts, e.getMessage());
                correction = PromptKit.buildCorrectionPrompt(result.getContent(), e.getMessage());
            }
        }
        throw lastError;
    }

    /** 子类可覆盖：默认系统提示词 */
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return PromptKit.DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 子类可覆盖：收集多模态素材。
     * 默认行为：多模态类型节点自动携带流程输入的全部图片素材 + 节点绑定的图片URL。
     */
    protected List<AiInvoker.MediaItem> collectMedias(NodeDefinition node, FlowInput input) {
        List<AiInvoker.MediaItem> medias = new ArrayList<>();
        if (node.getType().supportsMedia() && input != null && input.hasImages()) {
            medias.addAll(input.getImages());
        }
        if (node.getType().supportsMedia() && node.getImageUrl() != null && !node.getImageUrl().isBlank()) {
            medias.add(AiInvoker.MediaItem.ofUrl(node.getName() + "-url", "image/png", node.getImageUrl().trim()));
        }
        return medias;
    }

    /** 组装输出要求（JSON字段契约 / 纯文本），公共逻辑见 {@link PromptKit} */
    protected String buildOutputRequirement(NodeDefinition node) {
        return PromptKit.buildOutputRequirement(node.getOutputSpec());
    }

    /** 未使用变量时自动追加的上下文数据段 */
    protected String buildAutoContext(NodeContext ctx) {
        StringBuilder sb = new StringBuilder();
        FlowInput input = ctx.getFlowInput();
        List<String> lines = new ArrayList<>();
        if (input != null && input.hasText()) {
            lines.add("- 流程输入数据：" + truncate(input.getText().trim(), 4000));
        }
        if (input != null && input.hasExcel()) {
            lines.add("- Excel数据（sheet=" + input.getExcel().sheetName()
                + "，共" + (input.getExcel().rows().size() + input.getExcel().truncated()) + "行）："
                + truncate(input.getExcel().toJsonText(), 12000));
        }
        for (String ancestorId : ctx.getAncestorIds()) {
            NodeOutput output = ctx.getUpstreamOutputs().get(ancestorId);
            if (output == null || output.getText() == null) {
                continue;
            }
            String name = ctx.getUpstreamNames().getOrDefault(ancestorId, ancestorId);
            lines.add("- 上游节点「" + name + "」输出：" + truncate(output.getText(), 6000));
        }
        if (lines.isEmpty()) {
            return "";
        }
        sb.append("\n\n【上下文数据】\n");
        lines.forEach(line -> sb.append(line).append("\n"));
        return sb.toString();
    }

    /** 解析模型输出：json契约时提取JSON，失败视为节点失败 */
    protected NodeOutput parseOutput(NodeDefinition node, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型未返回有效输出");
        }
        if (node.getOutputSpec() == null || !node.getOutputSpec().isJson()) {
            return new NodeOutput(content.trim(), null);
        }
        JsonNode json = JsonExtractor.extract(content);
        if (json == null) {
            throw new IllegalStateException("输出JSON解析失败，模型原始输出：" + truncate(content, 500));
        }
        List<String> missing = PromptKit.validateRequired(node.getOutputSpec(), json);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("必填字段缺失或为空：" + String.join("、", missing));
        }
        return new NodeOutput(content.trim(), json);
    }

    protected String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（已截断，共" + text.length() + "字符）";
    }

    /** 上游产出快捷访问（子类可用） */
    protected Map<String, NodeOutput> upstream(NodeContext ctx) {
        return new LinkedHashMap<>(ctx.getUpstreamOutputs());
    }
}
