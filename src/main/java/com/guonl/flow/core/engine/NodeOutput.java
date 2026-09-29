package com.guonl.flow.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.guonl.flow.ai.AiInvoker;
import lombok.Data;

import java.util.List;

/**
 * 节点执行产出：原始输出 + 解析后的JSON（当输出契约为json且解析成功时）。
 */
@Data
public class NodeOutput {

    /** 模型原始输出 */
    private String text;

    /** 解析后的JSON（可能为null） */
    private JsonNode json;

    /** 工具调用轨迹（仅工具调用节点可能非空） */
    private List<AiInvoker.ToolCallTrace> toolCalls;

    /** 本次产出的模型用量（模型名 + token统计，可能为null） */
    private Usage usage;

    public NodeOutput() {
    }

    public NodeOutput(String text, JsonNode json) {
        this.text = text;
        this.json = json;
    }

    /** 一次模型调用的用量快照（用于可观测统计） */
    public record Usage(String model, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        public static Usage of(AiInvoker.InvokeResult result) {
            return new Usage(result.getModel(), result.getPromptTokens(),
                result.getCompletionTokens(), result.getTotalTokens());
        }
    }
}
