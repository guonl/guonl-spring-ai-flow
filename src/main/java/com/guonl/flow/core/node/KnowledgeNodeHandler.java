package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.knowledge.KnowledgeService;
import com.guonl.flow.knowledge.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点（RAG）：将变量解析后的指令作为查询，向量检索绑定知识库的命中片段，
 * 以【知识库参考片段】注入提示词，模型基于参考资料作答；命中片段作为轨迹持久化展示。
 */
@Component
public class KnowledgeNodeHandler extends AbstractNodeHandler {

    private final KnowledgeService knowledgeService;

    public KnowledgeNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties, KnowledgeService knowledgeService) {
        super(aiInvoker, aiProperties);
        this.knowledgeService = knowledgeService;
    }

    @Override
    public NodeType supports() {
        return NodeType.KNOWLEDGE;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是基于知识库问答的助手。请优先依据提供的【知识库参考片段】作答，"
            + "引用片段中的事实与数据；片段中没有的信息需明确说明未找到参考资料，不要编造。";
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception {
        if (node.getPrompt() == null || node.getPrompt().isBlank()) {
            throw new IllegalArgumentException("节点「" + node.getName() + "」未配置处理指令");
        }
        if (node.getKnowledgeBaseId() == null) {
            throw new IllegalArgumentException("节点「" + node.getName() + "」未绑定知识库，请在属性面板选择");
        }
        // 1. 变量表：流程输入 + 全部祖先产出；解析后的指令即检索 query
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String ancestorId : ctx.getAncestorIds()) {
            VarResolver.registerNode(vars, ancestorId, ctx.getUpstreamOutputs().get(ancestorId));
        }
        String query = VarResolver.resolve(node.getPrompt(), vars).resolved();
        // 2. 知识库向量检索
        List<RetrievedChunk> chunks = knowledgeService.search(node.getKnowledgeBaseId(), query, node.getTopK());
        // 3. 调用+解析循环：解析/校验失败时把错误反馈给模型自纠（与基类流水线一致）
        String outputRequirement = buildOutputRequirement(node);
        String knowledgeBlock = chunks.isEmpty()
            ? "（未命中相关内容：请依据通用知识回答，并在回答开头说明「知识库中未找到参考资料」。）"
            : formatChunks(chunks);
        int maxAttempts = 1 + Math.max(0, aiProperties.getOutputRetryAttempts());
        String correction = null;
        IllegalStateException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String prompt = query + "\n\n【知识库参考片段】\n" + knowledgeBlock + "\n\n" + outputRequirement;
            if (correction != null) {
                prompt = prompt + "\n\n" + correction;
            }
            AiInvoker.InvokeResult result = aiInvoker.invoke(AiInvoker.InvokeRequest.builder()
                .bizKey(node.getId() + ":" + node.getName())
                .systemPrompt(resolveSystemPrompt(node))
                .userPrompt(prompt)
                .model(node.getModel())
                .temperature(node.getTemperature())
                .onDelta(ctx.getStreamSink())
                .useTools(false)
                .conversationId(ctx.getFlowInput() != null ? ctx.getFlowInput().getConversationId() : null)
                .memoryTurns(node.getMemoryTurns())
                .build());
            try {
                NodeOutput output = parseOutput(node, result.getContent());
                output.setToolCalls(toTrace(query, chunks));
                return output;
            } catch (IllegalStateException e) {
                lastError = e;
                correction = PromptKit.buildCorrectionPrompt(result.getContent(), e.getMessage());
            }
        }
        throw lastError;
    }

    /** 命中片段格式化为参考块（带序号/相似度/来源文档） */
    private String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append(String.format("[片段%d | 相似度%.4f | 来源：%s]%n", i + 1, c.score(), c.docName()));
            sb.append(truncate(c.text(), 2000)).append("\n");
        }
        return sb.toString();
    }

    /** 命中片段转为执行轨迹（复用工具调用轨迹链路持久化与前端展示） */
    private List<AiInvoker.ToolCallTrace> toTrace(String query, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            lines.add(String.format("[片段%d | 相似度%.4f | 来源：%s] %s", i + 1, c.score(), c.docName(), truncate(c.text(), 500)));
        }
        return List.of(new AiInvoker.ToolCallTrace("知识库检索", query, String.join("\n", lines)));
    }
}
