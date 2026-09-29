package com.guonl.flow.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.db.entity.FlowNodeExecutionDO;
import com.guonl.flow.db.entity.FlowRunDO;
import com.guonl.flow.db.entity.RunStatsDO;
import com.guonl.flow.db.mapper.FlowNodeExecutionMapper;
import com.guonl.flow.db.mapper.FlowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运行历史存储（MySQL）：flow_run 保存轮次概要，flow_node_execution 保存节点明细。
 * <p>写穿式持久化：节点进入 RUNNING 与终态时由 FlowEngine 实时同步（updateNode），
 * 运行结束时落 finish()；历史查询按需从库装配，不再有内存上限。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunStore {

    /** error 列安全截断长度（列宽 VARCHAR(2048)） */
    private static final int MAX_ERROR_LEN = 2000;

    private static final TypeReference<List<AiInvoker.ToolCallTrace>> TRACE_TYPE = new TypeReference<>() {
    };

    /** 轨迹JSON序列化器（ObjectMapper线程安全，此处独立实例避免依赖自动装配） */
    private static final ObjectMapper TRACE_MAPPER = new ObjectMapper();

    private final FlowRunMapper runMapper;

    private final FlowNodeExecutionMapper nodeMapper;

    /** 运行启动：写入 flow_run 概要 + 全部节点 PENDING 明细 */
    public void save(FlowExecution execution) {
        runMapper.insert(toRunDO(execution));
        List<FlowNodeExecutionDO> nodes = new ArrayList<>();
        int seq = 0;
        for (NodeExecution ne : execution.getNodes().values()) {
            nodes.add(toNodeDO(execution.getRunId(), seq++, ne));
        }
        if (!nodes.isEmpty()) {
            nodeMapper.insertBatch(nodes);
        }
    }

    /** 节点状态写穿更新（RUNNING/终态均调用；无对应行时为无害no-op，如Playground直连） */
    public void updateNode(String runId, NodeExecution ne) {
        nodeMapper.updateState(toNodeDO(runId, 0, ne));
    }

    /** 运行终态：更新 flow_run 状态/耗时/结束时间/总token */
    public void finish(FlowExecution execution) {
        FlowRunDO record = new FlowRunDO();
        record.setRunId(execution.getRunId());
        record.setStatus(execution.getStatus().name());
        record.setCostMillis(execution.getCostMillis());
        record.setEndAt(execution.getEndAt());
        record.setTotalTokens(execution.getTotalTokens());
        runMapper.updateFinish(record);
    }

    public FlowExecution get(String runId) {
        FlowRunDO run = runMapper.findById(runId);
        return run == null ? null : assemble(run);
    }

    public FlowExecution require(String runId) {
        FlowExecution execution = get(runId);
        if (execution == null) {
            throw new IllegalArgumentException("运行记录不存在或已被清理: " + runId);
        }
        return execution;
    }

    /** 历史列表（按开始时间倒序，装配各运行节点状态供前端展示） */
    public List<FlowExecution> list() {
        List<FlowRunDO> runs = runMapper.findAll();
        return assembleList(runs);
    }

    /**
     * 条件分页检索（运行ID/流程模糊、状态精确、时间范围，按开始时间倒序）。
     * 仅装配当前页的节点明细，避免全量装配。
     */
    public PageResult<FlowExecution> search(String runId, String flow, String status,
                                            String startDate, String endDate, int page, int size) {
        String rid = blankToNull(runId);
        String flowKw = blankToNull(flow);
        String st = blankToNull(status);
        // 日期字符串（yyyy-MM-dd）→ epoch 毫秒范围（本地时区，结束日含当天全天）
        Long fromMs = dateToMillis(startDate, true);
        Long toMs = dateToMillis(endDate, false);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = runMapper.countSearch(rid, flowKw, st, fromMs, toMs);
        List<FlowExecution> items = List.of();
        if (total > 0) {
            int offset = (safePage - 1) * safeSize;
            if (offset < total) {
                items = assembleList(runMapper.searchPage(rid, flowKw, st, fromMs, toMs, offset, safeSize));
            }
        }
        return new PageResult<>(items, total, safePage, safeSize);
    }

    /** yyyy-MM-dd → epoch 毫秒；start=true 取当天 00:00:00，否则 23:59:59；空返回 null，格式非法抛 IllegalArgumentException */
    private Long dateToMillis(String date, boolean startOfDay) {
        String d = blankToNull(date);
        if (d == null) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(d);
            return startOfDay
                ? localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : localDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式错误，应为 yyyy-MM-dd：" + d);
        }
    }

    /** 批量装配运行概要 + 节点明细 */
    private List<FlowExecution> assembleList(List<FlowRunDO> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        Map<String, List<FlowNodeExecutionDO>> byRun = nodeMapper
            .findByRunIds(runs.stream().map(FlowRunDO::getRunId).toList())
            .stream()
            .collect(Collectors.groupingBy(FlowNodeExecutionDO::getRunId,
                LinkedHashMap::new, Collectors.toList()));
        List<FlowExecution> result = new ArrayList<>(runs.size());
        for (FlowRunDO run : runs) {
            result.add(assemble(run, byRun.getOrDefault(run.getRunId(), List.of())));
        }
        return result;
    }

    /** Dashboard 统计：总运行数、成功/失败数、成功率、平均耗时、累计tokens */
    public Stats stats() {
        RunStatsDO s = runMapper.selectStats();
        Stats stats = new Stats();
        if (s != null) {
            stats.total = s.getTotal();
            stats.running = s.getRunning();
            stats.success = s.getSuccess();
            stats.failed = s.getFailed();
            stats.avgCostMillis = s.getAvgCostMillis();
            stats.totalTokens = s.getTotalTokens();
            stats.llmCalls = s.getLlmCalls() == null ? 0 : s.getLlmCalls();
        }
        if (stats.success + stats.failed > 0) {
            stats.successRate = Math.round(1000.0 * stats.success / (stats.success + stats.failed)) / 10.0;
        }
        return stats;
    }

    // ---------- 装配与转换 ----------

    private FlowExecution assemble(FlowRunDO run) {
        return assemble(run, nodeMapper.findByRunId(run.getRunId()));
    }

    private FlowExecution assemble(FlowRunDO run, List<FlowNodeExecutionDO> nodeRows) {
        FlowExecution e = new FlowExecution();
        e.setRunId(run.getRunId());
        e.setFlowId(run.getFlowId());
        e.setFlowName(run.getFlowName());
        e.setStatus(FlowExecution.Status.valueOf(run.getStatus()));
        e.setInputSummary(run.getInputSummary());
        e.setInputImages(run.getInputImages());
        e.setInputRows(run.getInputRows());
        e.setStartAt(run.getStartAt());
        e.setEndAt(run.getEndAt());
        nodeRows.stream()
            .sorted(Comparator.comparingInt(FlowNodeExecutionDO::getSeq))
            .forEach(n -> e.getNodes().put(n.getNodeId(), toNode(n)));
        return e;
    }

    private NodeExecution toNode(FlowNodeExecutionDO n) {
        NodeExecution ne = new NodeExecution();
        ne.setNodeId(n.getNodeId());
        ne.setNodeName(n.getNodeName());
        ne.setNodeType(NodeType.valueOf(n.getNodeType()));
        ne.setStatus(NodeExecution.Status.valueOf(n.getStatus()));
        ne.setOutput("".equals(n.getOutput()) ? null : n.getOutput());
        ne.setParsedJson("".equals(n.getParsedJson()) ? null : n.getParsedJson());
        ne.setError("".equals(n.getError()) ? null : n.getError());
        ne.setModel("".equals(n.getModel()) ? null : n.getModel());
        ne.setPromptTokens(n.getPromptTokens());
        ne.setCompletionTokens(n.getCompletionTokens());
        ne.setTotalTokens(n.getTotalTokens());
        ne.setCostMillis(n.getCostMillis());
        ne.setToolCalls(deserializeTrace(n.getToolCalls()));
        ne.setStartAt(n.getStartAt());
        ne.setEndAt(n.getEndAt());
        return ne;
    }

    private FlowRunDO toRunDO(FlowExecution e) {
        FlowRunDO d = new FlowRunDO();
        d.setRunId(e.getRunId());
        d.setFlowId(e.getFlowId());
        d.setFlowName(e.getFlowName());
        d.setStatus(e.getStatus().name());
        d.setInputSummary(e.getInputSummary());
        d.setInputImages(e.getInputImages());
        d.setInputRows(e.getInputRows());
        d.setCostMillis(e.getCostMillis());
        d.setTotalTokens(e.getTotalTokens());
        d.setStartAt(e.getStartAt());
        d.setEndAt(e.getEndAt());
        return d;
    }

    private FlowNodeExecutionDO toNodeDO(String runId, int seq, NodeExecution ne) {
        FlowNodeExecutionDO d = new FlowNodeExecutionDO();
        d.setRunId(runId);
        d.setSeq(seq);
        d.setNodeId(ne.getNodeId());
        d.setNodeName(ne.getNodeName());
        d.setNodeType(ne.getNodeType() == null ? NodeType.TEXT.name() : ne.getNodeType().name());
        d.setStatus(ne.getStatus() == null ? NodeExecution.Status.PENDING.name() : ne.getStatus().name());
        d.setOutput(ne.getOutput() == null ? "" : ne.getOutput());
        d.setParsedJson(ne.getParsedJson() == null ? "" : ne.getParsedJson());
        d.setError(truncate(ne.getError()));
        d.setModel(ne.getModel() == null ? "" : ne.getModel());
        d.setPromptTokens(ne.getPromptTokens() == null ? 0 : ne.getPromptTokens());
        d.setCompletionTokens(ne.getCompletionTokens() == null ? 0 : ne.getCompletionTokens());
        d.setTotalTokens(ne.getTotalTokens() == null ? 0 : ne.getTotalTokens());
        d.setCostMillis(ne.getCostMillis());
        d.setToolCalls(serializeTrace(ne.getToolCalls()));
        d.setStartAt(ne.getStartAt());
        d.setEndAt(ne.getEndAt());
        return d;
    }

    /** 空白字符串转 null（检索条件忽略空白） */
    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    /** 轨迹列表 → JSON文本（空返回null，避免无效更新） */
    private String serializeTrace(List<AiInvoker.ToolCallTrace> trace) {
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        try {
            return TRACE_MAPPER.writeValueAsString(trace);
        } catch (Exception e) {
            log.warn("[Store] tool trace serialize failed: {}", e.getMessage());
            return null;
        }
    }

    /** JSON文本 → 轨迹列表（空/损坏返回null） */
    private List<AiInvoker.ToolCallTrace> deserializeTrace(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return TRACE_MAPPER.readValue(json, TRACE_TYPE);
        } catch (Exception e) {
            log.warn("[Store] tool trace deserialize failed: {}", e.getMessage());
            return null;
        }
    }

    @lombok.Data
    public static class Stats {
        private int total;
        private int running;
        private int success;
        private int failed;
        private double successRate;
        private long avgCostMillis;
        private long totalTokens;
        /** 模型调用总次数（成功执行的LLM节点数，含重试后成功的节点） */
        private long llmCalls;
    }
}
