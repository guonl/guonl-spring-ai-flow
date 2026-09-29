package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.ai.JsonExtractor;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

/**
 * 输出评估节点（LLM-as-judge）：按评估维度对流程输入与上游输出打分并给出理由。
 * <p>固定 JSON 输出 {@code {score, passed, reason, dimensions}}；
 * {@code passed} 可接条件路由节点，低分走重试或人工兜底分支。</p>
 */
@Component
public class EvaluateNodeHandler extends AbstractNodeHandler {

    /** 未配置评估指令时的默认评估维度 */
    private static final String DEFAULT_PROMPT =
        "评估上述内容的整体质量。评估维度：准确性（内容是否正确、无事实错误）、"
            + "完整性（要素是否齐全、有无遗漏）、格式规范性（是否条理清晰、符合要求）。";

    public EvaluateNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.EVALUATE;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception {
        if (node.getPrompt() == null || node.getPrompt().isBlank()) {
            // 空指令兜底：按默认维度评估；未引用变量时自动上下文会带全流程输入与上游输出
            node.setPrompt(DEFAULT_PROMPT);
        }
        return super.execute(node, ctx);
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        return "你是严格的质量评估专家（LLM-as-judge）。独立、客观地按给定维度评估内容质量，"
            + "不迎合、不夸大；得分要有依据，理由需具体指出优点与问题所在。"
            + "只输出要求的JSON，不输出任何其他内容。";
    }

    /** 固定评估契约：mock 与真实模型都按字段行对齐输出 */
    @Override
    protected String buildOutputRequirement(NodeDefinition node) {
        return "【输出要求】仅输出一个JSON对象，不要输出任何其他文字、解释或代码块标记。字段定义如下：\n"
            + "- score：综合得分，0-100的整数（必填）\n"
            + "- passed：是否达标，true=达标（必填）\n"
            + "- reason：评估理由，具体指出优点与问题（必填）\n"
            + "- dimensions：各维度得分明细文本，如「准确性:90；完整性:85；格式规范性:88」（必填）\n";
    }

    @Override
    protected NodeOutput parseOutput(NodeDefinition node, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("评估模型未返回有效输出");
        }
        JsonNode json = JsonExtractor.extract(content);
        if (json == null) {
            throw new IllegalStateException("评估输出JSON解析失败，模型原始输出：" + truncate(content, 500));
        }
        JsonNode scoreNode = json.get("score");
        if (scoreNode == null || !scoreNode.canConvertToInt()) {
            throw new IllegalStateException("评估输出缺少整数型 score 字段，模型原始输出：" + truncate(content, 300));
        }
        int score = scoreNode.asInt();
        if (score < 0 || score > 100) {
            throw new IllegalStateException("score 超出 0-100 范围：" + score);
        }
        JsonNode passedNode = json.get("passed");
        if (passedNode == null || !passedNode.isBoolean()) {
            throw new IllegalStateException("评估输出缺少布尔型 passed 字段，模型原始输出：" + truncate(content, 300));
        }
        String reason = json.path("reason").asText("");
        if (reason.isBlank()) {
            throw new IllegalStateException("评估输出缺少 reason 字段");
        }
        String summary = "综合得分 " + score + "/100（" + (passedNode.asBoolean() ? "达标" : "不达标") + "）：" + reason;
        return new NodeOutput(summary, json);
    }
}
