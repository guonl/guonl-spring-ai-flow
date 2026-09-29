package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.core.engine.FlowEngine;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.EdgeDefinition;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.store.FlowStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子流程节点：引用另一个流程定义作为「宏」同步执行，支持流程复用。
 * <p>执行模型：prompt 模板经变量解析组装为子流程输入文本 → 子流程整体同步运行
 * （子流程的运行不单独落库，是父运行的一部分）→ 取子流程末端节点（无出边）的输出作为本节点产出。</p>
 * <p>注意：子流程内部节点任务提交到同一引擎线程池，深层嵌套时存在线程池占满的排队风险
 * （MVP 阶段接受：池容量由 executor-pool-size 控制，嵌套深度建议不超过2层）。</p>
 */
@Component
@RequiredArgsConstructor
public class SubflowNodeHandler implements NodeHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FlowStore flowStore;

    /** 延迟获取引擎：FlowEngine → NodeHandlerRegistry → 本类 → FlowEngine 的构造环靠 Provider 打破 */
    private final ObjectProvider<FlowEngine> engineProvider;

    @Override
    public NodeType supports() {
        return NodeType.SUBFLOW;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) throws Exception {
        if (!StringUtils.hasText(node.getSubflowId())) {
            throw new IllegalArgumentException("子流程节点未绑定流程ID（subflowId 为空）");
        }
        FlowDefinition sub = flowStore.require(node.getSubflowId());

        // prompt 模板 → 子流程输入文本（变量协议与普通节点一致：{{input}} / {{上游节点id}}）
        String inputText = resolveInputText(node, ctx);

        FlowInput subInput = FlowInput.builder()
            .text(inputText)
            .conversationId(ctx.getConversationId())
            .build();

        // 同步执行子流程（阻塞至全部节点终态，整体受父节点的节点超时控制）
        FlowExecution subExecution = engineProvider.getObject().execute(sub, subInput);

        return assembleOutput(sub, subExecution);
    }

    /** 用变量协议解析 prompt 作为子流程输入文本 */
    private String resolveInputText(NodeDefinition node, NodeContext ctx) {
        String template = StringUtils.hasText(node.getPrompt()) ? node.getPrompt() : "";
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String ancestorId : ctx.getAncestorIds()) {
            NodeOutput upstream = ctx.getUpstreamOutputs().get(ancestorId);
            if (upstream != null) {
                VarResolver.registerNode(vars, ancestorId, upstream);
            }
        }
        return VarResolver.resolve(template, vars).resolved();
    }

    /**
     * 汇总子流程末端节点（无出边的节点）输出：
     * 全部末端失败 → 抛异常（父节点置 FAILED）；否则成功末端的文本换行拼接，
     * 且当恰好一个成功末端带解析JSON时透传该JSON（支持下游 {@code {{sf1.json.路径}}} 引用）。
     */
    private NodeOutput assembleOutput(FlowDefinition sub, FlowExecution subExecution) {
        Set<String> terminalIds = terminalNodeIds(sub);
        List<NodeExecution> terminals = new ArrayList<>();
        for (NodeExecution ne : subExecution.getNodes().values()) {
            if (terminalIds.contains(ne.getNodeId())) {
                terminals.add(ne);
            }
        }
        if (terminals.isEmpty()) {
            throw new IllegalStateException("子流程没有末端节点可汇总（无节点或存在环）");
        }

        List<String> succeededNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonNode passthroughJson = null;
        int jsonTerminals = 0;

        for (NodeExecution ne : terminals) {
            if (ne.getStatus() == NodeExecution.Status.SUCCESS) {
                succeededNames.add(ne.getNodeName());
                if (StringUtils.hasText(ne.getOutput())) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(ne.getOutput());
                }
                if (StringUtils.hasText(ne.getParsedJson())) {
                    jsonTerminals++;
                    try {
                        passthroughJson = JSON.readTree(ne.getParsedJson());
                    } catch (Exception ignore) {
                        // parsedJson 非法时按纯文本处理
                    }
                }
            } else {
                failedNames.add(ne.getNodeName() + "(" + ne.getStatus() + ")");
            }
        }

        if (succeededNames.isEmpty()) {
            throw new IllegalStateException("子流程全部末端节点失败: " + String.join("、", failedNames));
        }

        return new NodeOutput(text.toString(), jsonTerminals == 1 ? passthroughJson : null);
    }

    /** 末端节点ID集合 = 子流程定义中没有任何出边的节点 */
    private Set<String> terminalNodeIds(FlowDefinition sub) {
        Set<String> withOutgoing = new HashSet<>();
        for (EdgeDefinition edge : sub.getEdges()) {
            withOutgoing.add(edge.getFrom());
        }
        Set<String> terminals = new HashSet<>();
        for (NodeDefinition n : sub.getNodes()) {
            if (!withOutgoing.contains(n.getId())) {
                terminals.add(n.getId());
            }
        }
        return terminals;
    }
}
