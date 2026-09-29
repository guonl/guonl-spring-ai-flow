package com.guonl.flow.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.model.EdgeDefinition;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.node.NodeContext;
import com.guonl.flow.core.node.NodeHandlerRegistry;
import com.guonl.flow.core.store.FlowStore;
import com.guonl.flow.core.store.RunStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 流程执行引擎：DAG拓扑调度器。
 * <p>入度为0的节点立即可执行；多个就绪节点提交线程池并行执行（并行分支）；
 * 节点成功后递减下游入度，全部上游成功的汇流点被调度执行（汇流）；
 * 任一节点失败时，其全部下游传递取消，流程整体标记失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodeHandlerRegistry handlerRegistry;

    private final AiProperties aiProperties;

    private final RunStore runStore;

    private final RunStreamHub streamHub;

    /** 流程定义存储：子流程节点运行期加载被引用流程，校验期做存在性与引用环检查 */
    private final FlowStore flowStore;

    private ExecutorService executor;

    @PostConstruct
    public void init() {
        executor = Executors.newFixedThreadPool(Math.max(2, aiProperties.getExecutorPoolSize()), r -> {
            Thread t = new Thread(r, "flow-node-" + UUID.randomUUID().toString().substring(0, 4));
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * 静态校验流程定义（不执行）。
     *
     * @return 错误列表（空表示合法）
     */
    public List<String> validate(FlowDefinition flow) {
        List<String> errors = new ArrayList<>();
        if (flow.getNodes() == null || flow.getNodes().isEmpty()) {
            errors.add("流程至少需要一个节点");
            return errors;
        }
        Map<String, NodeDefinition> nodeMap = new LinkedHashMap<>();
        for (NodeDefinition node : flow.getNodes()) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("存在未指定ID的节点");
                continue;
            }
            if (nodeMap.containsKey(node.getId())) {
                errors.add("节点ID重复: " + node.getId());
                continue;
            }
            nodeMap.put(node.getId(), node);
            if (node.getName() == null || node.getName().isBlank()) {
                errors.add("节点 " + node.getId() + " 缺少名称");
            }
            // MODERATION / EVALUATE / AGGREGATE 允许空指令：前两者 handler 内置默认行为（自动审核上下文/按默认维度评估），
            // AGGREGATE 不经模型，prompt 语义随聚合策略变化，专项校验见下
            if ((node.getPrompt() == null || node.getPrompt().isBlank()) && !node.getType().allowsBlankPrompt()) {
                errors.add("节点「" + displayName(node) + "」缺少处理指令");
            }
            if (node.getType() == NodeType.AGGREGATE) {
                String strategy = node.getAggregateStrategy();
                boolean template = NodeDefinition.AGGREGATE_TEMPLATE.equals(strategy);
                if (template && (node.getPrompt() == null || node.getPrompt().isBlank())) {
                    errors.add("聚合节点「" + displayName(node) + "」模板渲染策略需要配置合并模板");
                }
            }
            if (node.getType() == NodeType.ROUTER) {
                if (node.getRoutes() == null || node.getRoutes().isEmpty()) {
                    errors.add("路由节点「" + displayName(node) + "」至少需要配置一个分支");
                } else {
                    for (int i = 0; i < node.getRoutes().size(); i++) {
                        NodeDefinition.Route r = node.getRoutes().get(i);
                        if (r == null || r.getLabel() == null || r.getLabel().isBlank()) {
                            errors.add("路由节点「" + displayName(node) + "」第" + (i + 1) + "个分支缺少分支名");
                        }
                    }
                }
            }
            if (node.getType() == NodeType.SUBFLOW) {
                if (node.getSubflowId() == null || node.getSubflowId().isBlank()) {
                    errors.add("子流程节点「" + displayName(node) + "」未绑定流程");
                } else if (node.getSubflowId().equals(flow.getId())) {
                    errors.add("子流程节点「" + displayName(node) + "」不能引用自身所在流程");
                } else {
                    FlowDefinition sub = flowStore.get(node.getSubflowId());
                    if (sub == null) {
                        errors.add("子流程节点「" + displayName(node) + "」引用的流程不存在: " + node.getSubflowId());
                    } else if (hasSubflowCycle(node.getSubflowId(), new LinkedHashSet<>(Set.of(flow.getId())))) {
                        errors.add("子流程节点「" + displayName(node) + "」形成流程引用环");
                    }
                }
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> adj = new HashMap<>();
        for (EdgeDefinition edge : safeEdges(flow)) {
            if (edge.getFrom() == null || edge.getTo() == null) {
                errors.add("连线缺少端点");
                continue;
            }
            if (edge.getFrom().equals(edge.getTo())) {
                errors.add("不允许自环连线: " + edge.getFrom());
                continue;
            }
            if (!nodeMap.containsKey(edge.getFrom()) || !nodeMap.containsKey(edge.getTo())) {
                errors.add("连线引用了不存在的节点: " + edge.getFrom() + " -> " + edge.getTo());
                continue;
            }
            NodeDefinition fromNode = nodeMap.get(edge.getFrom());
            if (fromNode != null && fromNode.getType() == NodeType.ROUTER
                    && edge.getCondition() != null && !edge.getCondition().isBlank()) {
                boolean matched = fromNode.getRoutes() != null && fromNode.getRoutes().stream()
                        .anyMatch(r -> r.getLabel() != null && r.getLabel().trim().equals(edge.getCondition().trim()));
                if (!matched) {
                    errors.add("连线 " + edge.getFrom() + " -> " + edge.getTo()
                            + " 的分支条件「" + edge.getCondition() + "」不在路由节点分支列表中");
                }
            }
            boolean added = adj.computeIfAbsent(edge.getFrom(), k -> new LinkedHashSet<>()).add(edge.getTo());
            if (added) {
                indegree.merge(edge.getTo(), 1, Integer::sum);
            }
        }
        // Kahn检测环
        Deque<String> queue = new ArrayDeque<>();
        nodeMap.keySet().forEach(id -> {
            indegree.putIfAbsent(id, 0);
            if (indegree.get(id) == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : adj.getOrDefault(cur, Set.of())) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < nodeMap.size()) {
            errors.add("流程存在循环依赖，请检查连线");
        }
        return errors;
    }

    /**
     * 沿 SUBFLOW 引用链检测环：{@code chain} 为当前引用路径上的流程ID集合，
     * 若被引用流程的 SUBFLOW 节点引用回链中任一流程则为环。深度截断5层防超深递归。
     */
    private boolean hasSubflowCycle(String subflowId, Set<String> chain) {
        if (chain.contains(subflowId)) {
            return true;
        }
        if (chain.size() >= 5) {
            return false;
        }
        FlowDefinition sub = flowStore.get(subflowId);
        if (sub == null) {
            return false;
        }
        chain.add(subflowId);
        try {
            for (NodeDefinition n : sub.getNodes()) {
                if (n.getType() == NodeType.SUBFLOW && n.getSubflowId() != null && !n.getSubflowId().isBlank()) {
                    if (hasSubflowCycle(n.getSubflowId(), chain)) {
                        return true;
                    }
                }
            }
        } finally {
            chain.remove(subflowId);
        }
        return false;
    }

    /**
     * 预备运行：静态校验并构建运行快照（全部节点PENDING）。
     * <p>先注册快照（含runId）到运行存储，再异步调用 run，即可实现「提交即返回runId + 前端轮询」。</p>
     */
    public FlowExecution prepare(FlowDefinition flow, FlowInput input) {
        List<String> errors = validate(flow);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("流程定义不合法: " + String.join("；", errors));
        }
        FlowExecution execution = new FlowExecution();
        execution.setRunId("r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        execution.setFlowId(flow.getId());
        execution.setFlowName(flow.getName());
        execution.setStartAt(System.currentTimeMillis());
        execution.setInput(input);
        String textSummary = input != null && input.hasText() ? input.getText().trim() : "";
        execution.setInputSummary(textSummary.substring(0, Math.min(120, textSummary.length())));
        execution.setInputImages(input != null && input.hasImages() ? input.getImages().size() : 0);
        execution.setInputRows(input != null && input.hasExcel() ? input.getExcel().rows().size() : 0);
        flow.getNodes().forEach(node -> {
            NodeExecution ne = new NodeExecution();
            ne.setNodeId(node.getId());
            ne.setNodeName(node.getName());
            ne.setNodeType(node.getType());
            execution.getNodes().put(node.getId(), ne);
        });
        return execution;
    }

    /**
     * 执行流程（阻塞至全部节点终态）。调用方应放在异步线程中执行。
     */
    public FlowExecution execute(FlowDefinition flow, FlowInput input) {
        FlowExecution execution = prepare(flow, input);
        run(execution, flow, input);
        return execution;
    }

    /**
     * 调度执行（阻塞至全部节点终态）。需先调用 {@link #prepare} 获得运行快照。
     */
    public void run(FlowExecution execution, FlowDefinition flow, FlowInput input) {
        try {
            Map<String, NodeDefinition> nodeMap = flow.getNodes().stream()
                .collect(Collectors.toMap(NodeDefinition::getId, n -> n, (a, b) -> a, LinkedHashMap::new));
            Map<String, List<String>> downstream = new HashMap<>();
            Map<String, Set<String>> upstream = new HashMap<>();
            Map<String, List<EdgeDefinition>> outEdges = new HashMap<>();
            for (EdgeDefinition edge : safeEdges(flow)) {
                downstream.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
                upstream.computeIfAbsent(edge.getTo(), k -> new LinkedHashSet<>()).add(edge.getFrom());
                outEdges.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
            }
            Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
            nodeMap.keySet().forEach(id -> remaining.put(id, new AtomicInteger(upstream.getOrDefault(id, Set.of()).size())));

            // 全量祖先（传递闭包，BFS收集）
            Map<String, List<String>> ancestors = new HashMap<>();
            for (String id : nodeMap.keySet()) {
                ancestors.put(id, collectAncestors(id, upstream));
            }

            CountDownLatch latch = new CountDownLatch(nodeMap.size());
            Map<String, AtomicBoolean> counted = new ConcurrentHashMap<>();
            nodeMap.keySet().forEach(id -> counted.put(id, new AtomicBoolean(false)));
            AtomicBoolean anyFailed = new AtomicBoolean(false);
            Set<String> cancelled = ConcurrentHashMap.newKeySet();

            List<String> roots = nodeMap.keySet().stream()
                .filter(id -> remaining.get(id).get() == 0).toList();
            log.info("[Flow] {} ({}) start, nodes={}, roots={}", execution.getRunId(), flow.getName(), nodeMap.size(), roots);
            roots.forEach(id -> submit(execution, nodeMap, id, ancestors, upstream, downstream, outEdges, remaining, latch, counted, anyFailed, cancelled));

            awaitTermination(latch, nodeMap.size());

            execution.setStatus(anyFailed.get() ? FlowExecution.Status.FAILED : FlowExecution.Status.SUCCESS);
            execution.setEndAt(System.currentTimeMillis());
            // 写穿：运行终态落库
            runStore.finish(execution);
            log.info("[Flow] {} finished, status={}, cost={}ms, success={}/{}",
                execution.getRunId(), execution.getStatus(), execution.getCostMillis(), execution.getSuccessCount(), nodeMap.size());
        } finally {
            // 无论成败，通知全部 SSE 订阅者运行已结束
            streamHub.complete(execution.getRunId());
        }
    }

    private void awaitTermination(CountDownLatch latch, int nodeCount) {
        long totalTimeoutMs = (long) nodeCount * aiProperties.getNodeTimeoutSeconds() * 1000 + 30_000;
        try {
            if (!latch.await(Math.max(totalTimeoutMs, 60_000), TimeUnit.MILLISECONDS)) {
                log.warn("[Flow] execution wait timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(FlowExecution execution,
                        Map<String, NodeDefinition> nodeMap,
                        String nodeId,
                        Map<String, List<String>> ancestors,
                        Map<String, Set<String>> upstream,
                        Map<String, List<String>> downstream,
                        Map<String, List<EdgeDefinition>> outEdges,
                        Map<String, AtomicInteger> remaining,
                        CountDownLatch latch,
                        Map<String, AtomicBoolean> counted,
                        AtomicBoolean anyFailed,
                        Set<String> cancelled) {
        NodeDefinition node = nodeMap.get(nodeId);
        NodeExecution ne = execution.getNodes().get(nodeId);
        ne.setStatus(NodeExecution.Status.RUNNING);
        ne.setStartAt(System.currentTimeMillis());
        // 写穿：节点进入RUNNING即落库
        runStore.updateNode(execution.getRunId(), ne);
        // 构建执行上下文：此时全部祖先已终态成功
        List<String> ancestorIds = ancestors.getOrDefault(nodeId, List.of());
        Map<String, NodeOutput> upstreamOutputs = new LinkedHashMap<>();
        Map<String, String> upstreamNames = new LinkedHashMap<>();
        for (String aid : ancestorIds) {
            upstreamOutputs.put(aid, toOutput(execution.getNodes().get(aid)));
            upstreamNames.put(aid, nodeMap.get(aid).getName());
        }
        NodeContext ctx = new NodeContext(execution.getInput(), upstreamOutputs, upstreamNames, ancestorIds,
            // 直接上游（仅有边直连，按流程定义顺序）：聚合节点按此合并产出
            nodeMap.keySet().stream()
                .filter(id -> upstream.getOrDefault(nodeId, Set.of()).contains(id))
                .toList(),
            delta -> streamHub.publish(execution.getRunId(), nodeId, delta),
            execution.getInput().getConversationId());

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return handlerRegistry.get(node).execute(node, ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("节点执行被中断");
                } catch (Exception e) {
                    throw new IllegalStateException(rootMessage(e), e);
                }
            }, executor)
            .orTimeout(aiProperties.getNodeTimeoutSeconds(), TimeUnit.SECONDS)
            .whenComplete((output, err) -> {
                ne.setEndAt(System.currentTimeMillis());
                ne.setCostMillis(ne.getEndAt() - ne.getStartAt());
                boolean failed = err != null;
                if (failed) {
                    Throwable cause = err instanceof TimeoutException
                        ? new IllegalStateException("节点执行超时（" + aiProperties.getNodeTimeoutSeconds() + "s）") : err;
                    ne.setStatus(NodeExecution.Status.FAILED);
                    ne.setError(rootMessage(cause));
                    anyFailed.set(true);
                    log.warn("[Flow] node {} ({}) failed: {}", nodeId, node.getName(), ne.getError());
                } else {
                    ne.setStatus(NodeExecution.Status.SUCCESS);
                    ne.setOutput(output.getText());
                    ne.setParsedJson(output.getJson() == null ? null : output.getJson().toString());
                    ne.setToolCalls(output.getToolCalls());
                    // 模型用量快照（可观测：token统计与实际模型名）
                    NodeOutput.Usage usage = output.getUsage();
                    if (usage != null) {
                        ne.setModel(usage.model());
                        ne.setPromptTokens(usage.promptTokens());
                        ne.setCompletionTokens(usage.completionTokens());
                        ne.setTotalTokens(usage.totalTokens());
                    }
                    log.info("[Flow] node {} ({}) success, cost={}ms", nodeId, node.getName(), ne.getCostMillis());
                }
                // 写穿：节点终态落库
                runStore.updateNode(execution.getRunId(), ne);
                countDown(nodeId, latch, counted);
                for (String next : downstream.getOrDefault(nodeId, List.of())) {
                    if (failed) {
                        cancelDownstream(execution, next, downstream, latch, counted);
                        continue;
                    }
                    EdgeDefinition edge = findEdge(outEdges, nodeId, next);
                    if (!edgeActive(node, output, edge, outEdges)) {
                        // 路由未命中的分支：目标及其下游级联取消
                        if (cancelled.add(next)) {
                            cancelDownstream(execution, next, downstream, latch, counted);
                        }
                        continue;
                    }
                    if (cancelled.contains(next)) {
                        continue; // 已被其他分支取消，防止并行上游晚完成误提交
                    }
                    if (remaining.get(next).decrementAndGet() == 0) {
                        submit(execution, nodeMap, next, ancestors, upstream, downstream, outEdges, remaining, latch, counted, anyFailed, cancelled);
                    }
                }
            });
    }

    /** 上游失败：后继全部取消（仅未开始的节点） */
    private void cancelDownstream(FlowExecution execution, String nodeId,
                                  Map<String, List<String>> downstream,
                                  CountDownLatch latch,
                                  Map<String, AtomicBoolean> counted) {
        NodeExecution ne = execution.getNodes().get(nodeId);
        if (ne == null || ne.getStatus() != NodeExecution.Status.PENDING) {
            return;
        }
        ne.setStatus(NodeExecution.Status.CANCELLED);
        ne.setError("未命中路由分支或上游失败，本节点已取消");
        // 写穿：取消状态落库
        runStore.updateNode(execution.getRunId(), ne);
        log.info("[Flow] node {} cancelled due to upstream failure", nodeId);
        countDown(nodeId, latch, counted);
        for (String next : downstream.getOrDefault(nodeId, List.of())) {
            cancelDownstream(execution, next, downstream, latch, counted);
        }
    }

    /** 判断 from→edge 这条出边是否激活：ROUTER 按路由结果筛选，其他节点恒激活 */
    private boolean edgeActive(NodeDefinition node, NodeOutput output, EdgeDefinition edge,
                               Map<String, List<EdgeDefinition>> outEdges) {
        if (node.getType() != NodeType.ROUTER) {
            return true;
        }
        String route = output == null || output.getText() == null ? "" : output.getText().trim();
        if (edge == null) {
            return true;
        }
        String cond = edge.getCondition();
        if (cond != null && !cond.isBlank()) {
            return cond.trim().equals(route);
        }
        // 默认边：仅当没有任何条件边命中路由时激活
        boolean anyHit = outEdges.getOrDefault(node.getId(), List.of()).stream()
                .anyMatch(e -> e.getCondition() != null && !e.getCondition().isBlank()
                        && e.getCondition().trim().equals(route));
        return !anyHit;
    }

    private EdgeDefinition findEdge(Map<String, List<EdgeDefinition>> outEdges, String from, String to) {
        return outEdges.getOrDefault(from, List.of()).stream()
                .filter(e -> to.equals(e.getTo()))
                .findFirst().orElse(null);
    }

    private void countDown(String nodeId, CountDownLatch latch, Map<String, AtomicBoolean> counted) {
        if (counted.getOrDefault(nodeId, new AtomicBoolean(false)).compareAndSet(false, true)) {
            latch.countDown();
        }
    }

    private NodeOutput toOutput(NodeExecution ne) {
        if (ne == null || ne.getStatus() != NodeExecution.Status.SUCCESS) {
            return null;
        }
        JsonNode json = null;
        if (ne.getParsedJson() != null) {
            try {
                json = MAPPER.readTree(ne.getParsedJson());
            } catch (Exception ignored) {
                // 上游非JSON输出时按纯文本处理
            }
        }
        return new NodeOutput(ne.getOutput(), json);
    }

    /** 收集全部传递祖先 */
    private List<String> collectAncestors(String nodeId, Map<String, Set<String>> upstream) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(upstream.getOrDefault(nodeId, Set.of()));
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (result.add(cur)) {
                queue.addAll(upstream.getOrDefault(cur, Set.of()));
            }
        }
        return new ArrayList<>(result);
    }

    private List<EdgeDefinition> safeEdges(FlowDefinition flow) {
        return flow.getEdges() == null ? List.of() : flow.getEdges();
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private String displayName(NodeDefinition node) {
        return node.getName() != null && !node.getName().isBlank() ? node.getName() : node.getId();
    }
}
