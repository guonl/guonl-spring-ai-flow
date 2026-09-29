package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.ai.JsonExtractor;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件路由节点：模型从候选分支中选一个，输出 {"route": "分支名"}，
 * NodeOutput.text 即路由结果；引擎据此决定哪些下游分支被激活。
 */
@Component
public class RouterNodeHandler extends AbstractNodeHandler {

    public RouterNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.ROUTER;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        return "你是流程条件路由器。根据用户给出的上下文，从候选分支中选出最匹配的一个，"
                + "只输出 {\"route\": \"分支名\"} 格式的JSON，不要输出任何其他内容。";
    }

    @Override
    protected String buildOutputRequirement(NodeDefinition node) {
        StringBuilder sb = new StringBuilder("【输出要求】仅输出一个JSON对象：{\"route\": \"分支名\"}。候选分支如下：\n");
        for (NodeDefinition.Route route : routesOf(node)) {
            sb.append("- ").append(route.getLabel())
                    .append(route.getDesc() == null || route.getDesc().isBlank() ? "" : "：" + route.getDesc())
                    .append("\n");
        }
        return sb.toString();
    }

    @Override
    protected NodeOutput parseOutput(NodeDefinition node, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("路由模型未返回有效输出");
        }
        JsonNode json = JsonExtractor.extract(content);
        if (json == null || json.get("route") == null || json.get("route").asText().isBlank()) {
            throw new IllegalStateException("路由输出缺少 route 字段，模型原始输出：" + truncate(content, 300));
        }
        String route = json.get("route").asText().trim();
        List<String> labels = new ArrayList<>();
        for (NodeDefinition.Route r : routesOf(node)) {
            labels.add(r.getLabel());
        }
        if (!labels.contains(route)) {
            throw new IllegalStateException("路由结果「" + route + "」不在候选分支" + labels + "中");
        }
        return new NodeOutput(route, json);
    }

    private List<NodeDefinition.Route> routesOf(NodeDefinition node) {
        return node.getRoutes() == null ? List.of() : node.getRoutes();
    }
}
