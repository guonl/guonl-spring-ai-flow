package com.guonl.flow.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.ai.ToolSetProvider;
import com.guonl.flow.core.engine.FlowEngine;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.model.EdgeDefinition;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.core.store.FlowStore;
import com.guonl.flow.core.store.RunStore;
import com.guonl.flow.core.tool.FlowTools;
import com.guonl.flow.knowledge.KnowledgeService;
import com.guonl.flow.knowledge.RetrievedChunk;
import com.guonl.flow.db.entity.KnowledgeBaseDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手工具集：查询流程/运行/统计/知识库 + 保存流程（NL建流程闭环）。
 * <p>经 {@link ToolSetProvider} 注册（toolSet="assistant"），仅 provider=llm 且
 * flow.assistant.tools-enabled=true 时由 {@code AssistantService} 挂载给模型自主调用；
 * 执行轨迹经 ToolContext 回传（与流程引擎工具同一套 Trace 格式）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTools implements ToolSetProvider {

    /** 工具集标识（与 InvokeRequest.toolSet 对应） */
    public static final String SET_NAME = "assistant";

    /** JSON序列化器（ObjectMapper线程安全；独立实例避免依赖自动装配，与全项目惯例一致） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowStore flowStore;

    private final RunStore runStore;

    private final KnowledgeService knowledgeService;

    private final FlowEngine flowEngine;

    @Override
    public String toolSet() {
        return SET_NAME;
    }

    @Override
    public Object tools() {
        return this;
    }

    // ---------- 只读工具 ----------

    @Tool(description = "查询平台的流程列表（名称/节点数/更新时间）。需要了解用户有哪些流程、找某个流程时调用；keyword 可为流程名或描述的关键词，传空字符串查全部")
    public String list_flows(@ToolParam(description = "关键词（流程名/描述模糊匹配，空字符串=全部）") String keyword,
                             ToolContext toolContext) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        PageResult<FlowDefinition> page = flowStore.search(kw, 1, 20);
        List<FlowDefinition> flows = page.items();
        if (flows.isEmpty()) {
            return FlowTools.record("list_flows", keyword, kw == null ? "当前没有任何流程" : "没有匹配「" + kw + "」的流程", toolContext);
        }
        StringBuilder sb = new StringBuilder("共 ").append(page.total()).append(" 个流程：\n");
        for (FlowDefinition f : flows) {
            sb.append("- id=").append(f.getId())
                .append(" | name=").append(f.getName())
                .append(" | nodes=").append(f.getNodes().size())
                .append(" | updatedAt=").append(f.getUpdatedAt()).append("\n");
        }
        return FlowTools.record("list_flows", keyword, sb.toString(), toolContext);
    }

    @Tool(description = "查看某个流程的完整定义（节点职责/连线/prompt）。需要分析、讲解或修改流程结构前先调用；flowId 形如 f_xxx")
    public String get_flow(@ToolParam(description = "流程ID，形如 f_xxx（可先用 list_flows 查询）") String flowId,
                           ToolContext toolContext) {
        FlowDefinition flow;
        try {
            flow = flowStore.require(flowId == null ? "" : flowId.trim());
        } catch (IllegalArgumentException e) {
            return FlowTools.record("get_flow", flowId, "流程不存在: " + flowId + "（可先用 list_flows 查询）", toolContext);
        }
        StringBuilder sb = new StringBuilder("流程 ").append(flow.getName())
            .append("（id=").append(flow.getId()).append("）\n");
        if (StringUtils.hasText(flow.getDescription())) {
            sb.append("描述：").append(flow.getDescription()).append("\n");
        }
        sb.append("节点（").append(flow.getNodes().size()).append("）：\n");
        for (NodeDefinition n : flow.getNodes()) {
            sb.append("- id=").append(n.getId()).append(" | name=").append(n.getName())
                .append(" | type=").append(n.getType().name());
            if (StringUtils.hasText(n.getPrompt())) {
                String p = n.getPrompt().replaceAll("\\s+", " ");
                sb.append(" | prompt=").append(p.length() > 80 ? p.substring(0, 80) + "…" : p);
            }
            sb.append("\n");
        }
        if (!flow.getEdges().isEmpty()) {
            sb.append("连线：\n");
            for (var e : flow.getEdges()) {
                sb.append("- ").append(e.getFrom()).append(" -> ").append(e.getTo());
                if (StringUtils.hasText(e.getCondition())) {
                    sb.append("（condition=").append(e.getCondition()).append("）");
                }
                sb.append("\n");
            }
        }
        return FlowTools.record("get_flow", flowId, sb.toString(), toolContext);
    }

    @Tool(description = "查看一次流程运行的执行记录（状态/耗时/tokens/各节点状态与错误信息）。用户给出运行ID（形如 r_xxx）要求查看或诊断失败原因时调用")
    public String get_run(@ToolParam(description = "运行ID，形如 r_xxx") String runId,
                          ToolContext toolContext) {
        FlowExecution run = runStore.get(runId == null ? "" : runId.trim());
        if (run == null) {
            return FlowTools.record("get_run", runId, "运行不存在: " + runId + "（可在运行历史页确认ID）", toolContext);
        }
        StringBuilder sb = new StringBuilder("运行 ").append(run.getRunId())
            .append(" | flow=").append(run.getFlowName())
            .append(" | status=").append(run.getStatus())
            .append(" | costMillis=").append(run.getCostMillis())
            .append(" | totalTokens=").append(run.getTotalTokens()).append("\n");
        for (NodeExecution ne : run.getNodes().values()) {
            sb.append("- ").append(ne.getNodeName()).append("（").append(ne.getNodeId()).append("）: ")
                .append(ne.getStatus());
            if (StringUtils.hasText(ne.getError())) {
                String err = ne.getError().replaceAll("\\s+", " ");
                sb.append(" | error=").append(err.length() > 200 ? err.substring(0, 200) + "…" : err);
            }
            sb.append("\n");
        }
        return FlowTools.record("get_run", runId, sb.toString(), toolContext);
    }

    @Tool(description = "列出近期失败的流程运行（运行ID/流程名/失败节点错误摘要）。用户想排查最近哪些运行出问题、批量分析失败时调用")
    public String recent_failed_runs(@ToolParam(description = "最多返回条数（1-20）") int limit,
                                     ToolContext toolContext) {
        int safe = Math.min(Math.max(limit, 1), 20);
        List<FlowExecution> failed = runStore.list().stream()
            .filter(r -> r.getStatus() == FlowExecution.Status.FAILED)
            .limit(safe)
            .toList();
        if (failed.isEmpty()) {
            return FlowTools.record("recent_failed_runs", String.valueOf(limit), "近期没有失败的运行", toolContext);
        }
        StringBuilder sb = new StringBuilder("近期失败运行 ").append(failed.size()).append(" 条：\n");
        for (FlowExecution r : failed) {
            sb.append("- ").append(r.getRunId()).append(" | ").append(r.getFlowName()).append("：");
            String err = firstError(r);
            sb.append(err == null ? "（无错误详情）" : err.length() > 120 ? err.substring(0, 120) + "…" : err).append("\n");
        }
        return FlowTools.record("recent_failed_runs", String.valueOf(limit), sb.toString(), toolContext);
    }

    @Tool(description = "在平台知识库中做向量检索，返回最相关的文档片段（含来源与相似度）。用户提问平台用法/业务知识且需要引用知识库内容时调用；没有知识库时返回提示")
    public String search_knowledge(@ToolParam(description = "检索问题或关键词") String query,
                                   @ToolParam(description = "返回片段数（1-10，默认4）") int topK,
                                   ToolContext toolContext) {
        List<KnowledgeBaseDO> bases = knowledgeService.list();
        if (bases.isEmpty()) {
            return FlowTools.record("search_knowledge", query, "平台还没有知识库。可引导用户到「知识库」页创建并上传文档", toolContext);
        }
        int safeTopK = Math.min(Math.max(topK, 1), 10);
        List<RetrievedChunk> hits = new ArrayList<>();
        for (KnowledgeBaseDO base : bases) {
            try {
                hits.addAll(knowledgeService.search(base.getId(), query, safeTopK));
            } catch (Exception e) {
                log.warn("[AssistantTools] knowledge search failed, kb={}: {}", base.getId(), e.getMessage());
            }
        }
        if (hits.isEmpty()) {
            return FlowTools.record("search_knowledge", query, "知识库中没有命中内容（可尝试换关键词，或确认文档已向量化完成）", toolContext);
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        StringBuilder sb = new StringBuilder("命中 ").append(Math.min(hits.size(), safeTopK)).append(" 个片段：\n");
        int i = 0;
        for (RetrievedChunk c : hits) {
            if (i++ >= safeTopK) {
                break;
            }
            String text = c.text().replaceAll("\\s+", " ");
            sb.append("[").append(i).append("] score=").append(String.format("%.3f", c.score()))
                .append(" doc=").append(c.docName()).append("\n")
                .append(text.length() > 300 ? text.substring(0, 300) + "…" : text).append("\n\n");
        }
        return FlowTools.record("search_knowledge", query, sb.toString(), toolContext);
    }

    // ---------- 写工具（NL 建流程） ----------

    @Tool(description = "保存一个新流程到平台（自然语言建流程的落库动作）。必须先与用户确认节点方案后再调用。nodesJson/edgesJson 为 JSON 数组文本，结构与平台流程定义一致；校验失败会返回错误列表，请修正后重试")
    public String save_flow(@ToolParam(description = "流程名称") String name,
                            @ToolParam(description = "流程描述（可为空字符串）") String description,
                            @ToolParam(description = "节点数组JSON，如 [{\"id\":\"n1\",\"name\":\"翻译\",\"type\":\"TEXT\",\"prompt\":\"...\"}]；type 取13种节点类型枚举名（TEXT/IMAGE/JSON/EXCEL/COMBINED/ROUTER/TOOL/KNOWLEDGE/IMAGE_GEN/MODERATION/EVALUATE/SUBFLOW/AGGREGATE）") String nodesJson,
                            @ToolParam(description = "连线数组JSON，如 [{\"from\":\"n1\",\"to\":\"n2\"}]；ROUTER 下游边可带 condition") String edgesJson,
                            ToolContext toolContext) {
        try {
            if (!StringUtils.hasText(name)) {
                return FlowTools.record("save_flow", name, "保存失败：流程名称不能为空", toolContext);
            }
            FlowDefinition flow = new FlowDefinition();
            flow.setName(name.trim());
            if (StringUtils.hasText(description)) {
                flow.setDescription(description.trim());
            }
            List<NodeDefinition> nodes = MAPPER.readValue(nodesJson,
                MAPPER.getTypeFactory().constructCollectionType(List.class, NodeDefinition.class));
            List<EdgeDefinition> edges = StringUtils.hasText(edgesJson)
                ? MAPPER.readValue(edgesJson, MAPPER.getTypeFactory().constructCollectionType(List.class, EdgeDefinition.class))
                : List.of();
            flow.setNodes(nodes);
            flow.setEdges(edges);
            // 静态校验：错误回灌模型自纠（模型据错误列表修正后重新调用本工具）
            List<String> errors = flowEngine.validate(flow);
            if (!errors.isEmpty()) {
                return FlowTools.record("save_flow", name, "校验未通过，请修正后重试：\n- " + String.join("\n- ", errors), toolContext);
            }
            FlowDefinition saved = flowStore.save(flow);
            String result = "保存成功 flowId=" + saved.getId() + " editorUrl=/flow-editor?flowId=" + saved.getId();
            return FlowTools.record("save_flow", name, result, toolContext);
        } catch (Exception e) {
            log.warn("[AssistantTools] save_flow failed: {}", e.getMessage());
            return FlowTools.record("save_flow", name, "保存失败：" + e.getMessage() + "（请检查 nodesJson/edgesJson 格式）", toolContext);
        }
    }

    // ---------- 内部 ----------

    private String firstError(FlowExecution run) {
        for (NodeExecution ne : run.getNodes().values()) {
            if (ne.getStatus() == NodeExecution.Status.FAILED && StringUtils.hasText(ne.getError())) {
                return ne.getError().replaceAll("\\s+", " ");
            }
        }
        return null;
    }
}
