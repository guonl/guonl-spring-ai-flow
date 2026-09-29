package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 聚合节点：不经模型的多上游输出汇合，解决汇流点只能引用单个上游产出的痛点。
 * <p>三种合并策略（{@link NodeDefinition#getAggregateStrategy()}）：
 * <ul>
 *   <li><b>concat</b>（默认）：直接上游输出按定义序拼接，prompt 复用为分隔符（空则双换行）；</li>
 *   <li><b>jsonMerge</b>：每个直接上游一个字段（字段名取节点名，重名回退节点ID），
 *       值取其 JSON（无则取文本），输出 json 对象供下游 {@code {{节点ID.json.字段}}} 引用；</li>
 *   <li><b>template</b>：prompt 复用为合并模板，支持 {@code {{节点ID}}} / {@code {{节点名}}} /
 *       {@code {{input}}} 变量协议引用各上游产出，未命中变量替换为空串并告警。</li>
 * </ul>
 * <p>合并单位为<b>直接上游</b>（仅有边直连的父节点，按流程定义顺序）；无直接上游时回退全部祖先。
 * 不走模型链路，实现 {@link NodeHandler} 而非继承 AbstractNodeHandler。</p>
 */
@Component
public class AggregateNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(AggregateNodeHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public NodeType supports() {
        return NodeType.AGGREGATE;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception {
        String strategy = node.getAggregateStrategy();
        if (strategy == null || strategy.isBlank()) {
            strategy = NodeDefinition.AGGREGATE_CONCAT;
        }
        List<String> ids = mergeUnitIds(ctx);
        return switch (strategy) {
            case NodeDefinition.AGGREGATE_JSON_MERGE -> jsonMerge(node, ctx, ids);
            case NodeDefinition.AGGREGATE_TEMPLATE -> template(node, ctx, ids);
            default -> concat(node, ctx, ids);
        };
    }

    /** 合并单位：直接上游优先（定义序），无则回退全部祖先（拓扑序） */
    private List<String> mergeUnitIds(NodeContext ctx) {
        List<String> parentIds = ctx.getParentIds();
        return parentIds == null || parentIds.isEmpty() ? ctx.getAncestorIds() : parentIds;
    }

    /** 拼接策略：上游输出按序拼接，prompt 复用为分隔符 */
    private NodeOutput concat(NodeDefinition node, NodeContext ctx, List<String> ids) {
        String sep = node.getPrompt() == null || node.getPrompt().isBlank() ? "\n\n" : node.getPrompt();
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            NodeOutput out = ctx.getUpstreamOutputs().get(id);
            if (out == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(out.getText() == null ? "" : out.getText());
        }
        return new NodeOutput(sb.toString(), null);
    }

    /** JSON字段合并策略：每个上游一个字段（名取节点名，重名回退节点ID），值取其JSON（无则取文本） */
    private NodeOutput jsonMerge(NodeDefinition node, NodeContext ctx, List<String> ids) throws Exception {
        ObjectNode merged = MAPPER.createObjectNode();
        for (String id : ids) {
            NodeOutput out = ctx.getUpstreamOutputs().get(id);
            if (out == null) {
                continue;
            }
            String fieldName = ctx.getUpstreamNames().getOrDefault(id, id);
            if (merged.has(fieldName)) {
                fieldName = id; // 节点名重名：回退节点ID，避免字段覆盖
            }
            JsonNode json = out.getJson();
            merged.set(fieldName, json != null && !json.isMissingNode() ? json
                : MAPPER.valueToTree(out.getText() == null ? "" : out.getText()));
        }
        return new NodeOutput(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(merged), merged);
    }

    /** 模板渲染策略：prompt 复用为合并模板，复用变量协议（{{节点ID}} / {{节点名}} / {{input}}） */
    private NodeOutput template(NodeDefinition node, NodeContext ctx, List<String> ids) {
        String tpl = node.getPrompt();
        if (tpl == null || tpl.isBlank()) {
            throw new IllegalArgumentException("聚合节点「" + node.getName() + "」使用模板渲染策略但未配置合并模板");
        }
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String id : ids) {
            NodeOutput out = ctx.getUpstreamOutputs().get(id);
            VarResolver.registerNode(vars, id, out);
            // 节点名别名：{{节点名}} 同样可用（不覆盖已注册键，重名节点仅首个生效）
            String name = ctx.getUpstreamNames().get(id);
            if (name != null && !name.isBlank() && !vars.containsKey(name) && out != null) {
                vars.put(name, out.getText() == null ? "" : out.getText());
            }
        }
        VarResolver.ResolveResult result = VarResolver.resolve(tpl, vars);
        if (result.missingVars() > 0) {
            log.warn("[Flow] 聚合节点「{}」模板变量未命中 {} 处（已替换为空串）", node.getName(), result.missingVars());
        }
        return new NodeOutput(result.resolved(), null);
    }
}
