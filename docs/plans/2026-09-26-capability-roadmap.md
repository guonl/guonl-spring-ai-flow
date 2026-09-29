# Spring AI Flow 能力扩展路线图（Phase 1 详细 + Phase 2-4 概要）

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

## Goal

为 guonl-spring-ai-flow（AI 流程编排产品）分四个阶段补齐企业级能力：结构化输出可靠性、条件分支编排、调用重试降级、运行页流式体验（Phase 1，本文档详细展开），以及工具调用、记忆、RAG 知识库、MCP、多模态扩展与观测评估（Phase 2-4，概要规划）。

## Architecture

- 既有分层保持不变：`web`（控制器）→ `core/engine`（DAG 调度）→ `core/node`（节点处理器）→ `ai`（模型调用 SPI）。
- 新增能力全部走既有扩展点：节点类型 = `NodeType` 枚举 + `AbstractNodeHandler` 子类；模型行为 = `AiInvoker` SPI 实现；配置 = `AiProperties`（`flow.ai` 前缀）。
- 前端为原生 JS（`src/main/resources/static/`），节点类型需同步：`common.js` 的 `NT` 映射、`flow.css` 的 `--c-*` 变量、`flow-editor.html` 的 palette、`editor.js` 的属性面板。

## Tech Stack

Spring Boot 3.x、Spring AI 1.x（ChatClient / 流式 Flux）、Jackson、Servlet SSE（SseEmitter）、Lombok、原生 JS/CSS 前端。构建：`mvn -q compile`。

## 验证策略（偏离 TDD 的说明）

本项目当前无测试基础设施（仅默认上下文加载测试）。验证方式：

1. 每个任务完成后 `mvn -q compile` 必须通过（期望输出：无 ERROR，退出码 0）。
2. 手动验证：`application.yml` 已有 mock provider 配置（`flow.ai.provider` 切 `mock`），启动后通过编辑器 UI 或 curl 触发运行，观察日志与运行页表现。
3. 每个任务一次 git commit，便于回滚。

> **执行者注意**：实施每个任务前，必须先 Read 目标文件再修改——本文档给出的行号是编写时的快照，代码块必须与实际文件对齐后再落入。

---

## 路线总览

| 阶段 | 任务 | 内容 | 状态 |
|---|---|---|---|
| Phase 1 | Task 1 | E1 结构化输出升级：JSON 骨架注入 + 必填校验 + 纠错重试 | 本文档详细展开 |
| Phase 1 | Task 2 | A1 条件分支节点：ROUTER 节点 + 边条件 + 引擎调度 + 前端编辑 | 本文档详细展开 |
| Phase 1 | Task 3 | E2 重试/降级：瞬时错误指数退避 + fallback 模型兜底 | 本文档详细展开 |
| Phase 1 | Task 4 | F5 运行页流式：SSE 推送节点增量输出 | 本文档详细展开 |
| Phase 2 | 概要 | B1 工具调用节点、D1 JDBC 记忆、A2 聚合节点 | 见文末 |
| Phase 3 | 概要 | C1/C2 RAG 知识库、B2 MCP Server、F1/F3/F4 语音/生图/审核 | 见文末 |
| Phase 4 | 概要 | E3 评估、F6 可观测、A3 子流程、B3 MCP 客户端 | 见文末 |

---

## Task 1: E1 结构化输出升级（JSON 骨架 + 必填校验 + 纠错重试）

**目标**：JSON 类节点输出更稳——提示词注入 JSON 骨架示例、解析后校验必填字段、失败时把错误反馈给模型自纠重试。

**Files**

- Modify: `src/main/java/com/guonl/flow/config/AiProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/guonl/flow/core/node/PromptKit.java`
- Modify: `src/main/java/com/guonl/flow/core/node/AbstractNodeHandler.java`
- Modify: `src/main/java/com/guonl/flow/core/node/TextNodeHandler.java`、`ImageNodeHandler.java`、`JsonNodeHandler.java`、`ExcelNodeHandler.java`、`CombinedNodeHandler.java`（仅构造器）

**Steps**

### Step 1.1：AiProperties 加重试次数配置

在 `AiProperties`（`@ConfigurationProperties(prefix = "flow.ai")`）现有字段（`mockDelayMillis` 之后）追加：

```java
    /** 结构化输出解析/校验失败后的纠错重试次数 */
    private int outputRetryAttempts = 2;
```

### Step 1.2：application.yml 同步配置

在 `flow.ai:` 段（`mock-delay-millis` 附近）追加：

```yaml
      # 结构化输出纠错重试次数
      output-retry-attempts: 2
```

### Step 1.3：PromptKit 增强骨架与校验

1. import 区追加：`com.fasterxml.jackson.databind.JsonNode`、`java.util.ArrayList`、`java.util.List`。
2. `buildOutputRequirement(OutputSpec spec)` 保持签名与文本模式分支不变（`PlaygroundApiController:194` 依赖该 public static 签名）。仅在 JSON 分支「字段定义如下」的 else 块（字段列表 for 循环）末尾追加一行骨架输出：

```java
            sb.append("按如下JSON结构输出：").append(buildSkeleton(spec)).append("\n");
```

3. 类末尾（`buildOutputRequirement` 之后）追加以下方法：

```java
    /**
     * 根据字段定义构建 JSON 骨架示例，帮助模型对齐输出结构。
     */
    public static String buildSkeleton(OutputSpec spec) {
        StringBuilder sb = new StringBuilder("{");
        if (spec.getFields() != null) {
            for (OutputSpec.Field field : spec.getFields()) {
                if (field.getName() == null || field.getName().isBlank()) continue;
                if (sb.length() > 1) sb.append(", ");
                sb.append("\"").append(field.getName().trim()).append("\": ").append(sampleValue(field));
            }
        }
        return sb.append("}").toString();
    }

    /** 按字段描述启发式推断示例占位值 */
    private static String sampleValue(OutputSpec.Field field) {
        String hint = (field.getDesc() == null ? "" : field.getDesc()) + " " + field.getName();
        String h = hint == null ? "" : hint.toLowerCase();
        if (h.contains("数组") || h.contains("列表") || h.contains("array") || h.contains("list")) return "[...]";
        if (h.contains("数值") || h.contains("金额") || h.contains("数量") || h.contains("score")
                || h.contains("count") || h.contains("number") || h.contains("数字")) return "0";
        if (h.contains("布尔") || h.contains("是否") || h.contains("boolean")) return "true";
        return "\"...\"";
    }

    /**
     * 校验 JSON 输出是否满足必填字段，返回缺失或为空的必填字段名列表。
     */
    public static List<String> validateRequired(OutputSpec spec, JsonNode json) {
        List<String> missing = new ArrayList<>();
        if (spec == null || !spec.isJson() || spec.getFields() == null || json == null) {
            return missing;
        }
        for (OutputSpec.Field field : spec.getFields()) {
            if (field.getName() == null || field.getName().isBlank() || !field.isRequired()) continue;
            JsonNode v = json.get(field.getName().trim());
            if (v == null || v.isNull()
                    || (v.isTextual() && v.asText().isBlank())
                    || (v.isArray() && v.isEmpty())
                    || (v.isObject() && v.isEmpty())) {
                missing.add(field.getName().trim());
            }
        }
        return missing;
    }

    /**
     * 构建纠错重试提示词：附上一次的错误输出与失败原因，要求模型自纠。
     */
    public static String buildCorrectionPrompt(String lastOutput, String error) {
        return "【上一次输出不符合要求，请修正后重新输出】\n"
                + "失败原因：" + (error == null ? "未知" : error) + "\n"
                + "上一次输出：\n"
                + truncate(lastOutput, 2000) + "\n"
                + "请严格按照输出要求重新输出，不要重复之前的错误。";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…（已截断，共" + text.length() + "字符）";
    }
```

### Step 1.4：AbstractNodeHandler 接入纠错循环

1. import 追加 `com.guonl.flow.config.AiProperties`；字段与构造器改为双依赖：

```java
    protected final AiInvoker aiInvoker;
    protected final AiProperties aiProperties;

    protected AbstractNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        this.aiInvoker = aiInvoker;
        this.aiProperties = aiProperties;
    }
```

2. `buildOutputRequirement` 签名由 `(OutputSpec spec)` 改为 `(NodeDefinition node)`（仅基类自用，RouterNodeHandler 后续将覆盖它）：

```java
    protected String buildOutputRequirement(NodeDefinition node) {
        return PromptKit.buildOutputRequirement(node.getOutputSpec());
    }
```

3. `execute` 中原「3. 输出契约 / 5. 模型调用 / 6. 结果解析」三段，替换为纠错重试循环（步骤 1/2/4 原样保留）：

```java
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
                .build());
            try {
                return parseOutput(node, result.getContent());
            } catch (IllegalStateException e) {
                lastError = e;
                log.warn("[Node] {} output invalid (attempt {}/{}): {}", node.getId(), attempt, maxAttempts, e.getMessage());
                correction = PromptKit.buildCorrectionPrompt(result.getContent(), e.getMessage());
            }
        }
        throw lastError;
```

4. `parseOutput` 的 JSON 分支在 `JsonExtractor.extract` 成功后追加必填校验：

```java
        List<String> missing = PromptKit.validateRequired(node.getOutputSpec(), json);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("必填字段缺失或为空：" + String.join("、", missing));
        }
```

### Step 1.5：5 个子类构造器同步

`TextNodeHandler` / `ImageNodeHandler` / `JsonNodeHandler` / `ExcelNodeHandler` / `CombinedNodeHandler` 的构造器统一改为：

```java
    public XxxNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }
```

并各加 import `com.guonl.flow.config.AiProperties`。Handler 均 `@Component` 注册，Spring 自动注入两个 bean，无需其他改动。

**Run**

```
mvn -q compile
```

期望：BUILD SUCCESS，无编译错误。

手动验证（mock）：`flow.ai.provider` 设为 `mock` 启动，编辑器运行一个含 JSON 输出节点（如「合同审查」示例流程）的流程，观察：
- 节点日志出现骨架提示后模型仍正常输出（mock 按「字段定义如下」标记生成 JSON，骨架行不以 `- ` 开头，不破坏 `MockAiInvoker` 的 FIELD_LINE 正则）；
- 若日志出现 `[Node] ... output invalid (attempt ...)` 说明纠错循环生效；mock 场景若因 mockJson 缺必填字段导致重试后仍失败，属预期观察项，必要时微调 `MockAiInvoker` 使其生成全部字段。

**Commit**

```
git add -A && git commit -m "feat(flow): E1 结构化输出升级——JSON骨架注入+必填校验+纠错重试"
```

---

## Task 2: A1 条件分支节点（ROUTER + 边条件 + 引擎调度 + 前端编辑）

**目标**：新增「条件路由」节点——模型根据上游内容从预定义分支中选一个，只有命中分支的下游被调度，未命中分支级联取消。

**语义定稿**

- ROUTER 节点输出固定为 `{"route": "分支名"}`；`NodeOutput.text` 即路由名。
- `EdgeDefinition.condition` = 分支名；为空 = 默认边（仅当没有任何条件边命中时激活）。
- 未命中的出边：目标节点及其下游级联取消（复用 `cancelDownstream`）。
- 引擎需 `Set<String> cancelled` 防御竞态：R 先取消 T 后，并行上游 X 晚完成使 `remaining` 归 0，若无检查会误提交已取消节点。

**Files**

- Modify: `src/main/java/com/guonl/flow/core/model/NodeType.java`
- Modify: `src/main/java/com/guonl/flow/core/model/NodeDefinition.java`
- Modify: `src/main/java/com/guonl/flow/core/model/EdgeDefinition.java`
- Modify: `src/main/java/com/guonl/flow/core/store/FlowSeeder.java`（L83/L118 两处双参构造）
- Create: `src/main/java/com/guonl/flow/core/node/RouterNodeHandler.java`
- Modify: `src/main/java/com/guonl/flow/core/engine/FlowEngine.java`
- Modify: 前端四件套（见 Step 2.7）
- Modify: `docs/usage.md`（可选）

### Step 2.1：模型层三件

1. `NodeType` 枚举：`COMBINED(...)` 行尾分号改逗号，追加：

```java
    ROUTER("条件路由", "#ef4444", "🔀");
```

（`supportsMedia()` 不含 ROUTER，无需改动。）

2. `NodeDefinition` 追加字段与内嵌类（import `java.util.List`）：

```java
    /** 路由节点的候选分支（仅 ROUTER 类型使用） */
    private List<Route> routes;

    @Data
    public static class Route {
        private String label;
        private String desc;
    }
```

3. `EdgeDefinition` 追加字段（注意 `@AllArgsConstructor` 自动变三参，全项目唯一的双参调用在 FlowSeeder，见下一步）：

```java
    /** 条件分支标记：ROUTER 节点输出的分支名；null/空 = 默认边 */
    private String condition;
```

4. `FlowSeeder` 两处（约 L83/L118）改为三参：

```java
List.of(new EdgeDefinition("extract", "structure", null), new EdgeDefinition("structure", "script", null)));
...
List.of(new EdgeDefinition("doc", "verdict", null), new EdgeDefinition("excel", "verdict", null)));
```

### Step 2.2：RouterNodeHandler（新建）

```java
package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.ai.JsonExtractor;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.model.NodeDefinition;
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
```

> 无效 route 抛 `IllegalStateException`，正好被 Task 1 的基类纠错循环捕获自纠——两个任务天然衔接。

### Step 2.3：FlowEngine 校验增强（validate 方法）

1. 节点循环（`node.getPrompt()` 校验之后）追加：

```java
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
```

2. 边校验（端点存在性检查之后）追加 condition 与分支列表的一致性校验：

```java
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
```

3. import 追加 `com.guonl.flow.core.model.NodeType`。

### Step 2.4：FlowEngine 调度增强（run/submit）

1. `run()` 中 `downstream`/`upstream` 构建循环（约 L194-197）改为同时构建 `outEdges`：

```java
        Map<String, List<String>> downstream = new HashMap<>();
        Map<String, Set<String>> upstream = new HashMap<>();
        Map<String, List<EdgeDefinition>> outEdges = new HashMap<>();
        for (EdgeDefinition edge : safeEdges(flow)) {
            downstream.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
            upstream.computeIfAbsent(edge.getTo(), k -> new LinkedHashSet<>()).add(edge.getFrom());
            outEdges.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
        }
```

2. `run()` 中 `anyFailed` 声明之后追加（每次运行独立的取消集合）：

```java
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
```

3. `run()` 的 roots 提交与 `submit` 签名扩展（原 10 参 → 12 参，追加 `outEdges`、`cancelled`）：

```java
        roots.forEach(id -> submit(execution, nodeMap, id, ancestors, downstream, outEdges, remaining, latch, counted, anyFailed, cancelled));
```

```java
    private void submit(FlowExecution execution,
                        Map<String, NodeDefinition> nodeMap,
                        String nodeId,
                        Map<String, List<String>> ancestors,
                        Map<String, List<String>> downstream,
                        Map<String, List<EdgeDefinition>> outEdges,
                        Map<String, AtomicInteger> remaining,
                        CountDownLatch latch,
                        Map<String, AtomicBoolean> counted,
                        AtomicBoolean anyFailed,
                        Set<String> cancelled) {
```

4. `whenComplete` 成功分支的下游推进循环（原 `for (String next : ...)` 的 else-if 部分）改为：

```java
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
                        submit(execution, nodeMap, next, ancestors, downstream, outEdges, remaining, latch, counted, anyFailed, cancelled);
                    }
                }
```

> 注意：原代码失败分支不带 `continue`（for 内 if/else if 结构），改造后必须加 `continue` 保持语义等价。

5. 类内新增两个私有方法：

```java
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
```

6. `cancelDownstream` 的取消文案微调（可选）：`ne.setError("上游节点失败，本节点已取消")` 改为同时覆盖路由取消场景：`"未命中路由分支或上游失败，本节点已取消"`。

### Step 2.5：Run（后端）

```
mvn -q compile
```

期望：BUILD SUCCESS。

### Step 2.6：前端——节点类型与分支编辑器

> editor.js（约 580 行）实施前先 Read 目标区段，以下行号为快照参考。

1. `static/common.js` 的 `NT` 映射（约 L6-12）追加一项：`router: "条件路由"`。
2. `static/flow.css` 的 `--c-*` 变量区（约 L26-30）追加：`--c-router: #ef4444;`。
3. `static/flow-editor.html` palette 区（约 L33-58）追加：

```html
<div class="palette-item" draggable="true" data-type="router" style="--dot: var(--c-router)">
  <span class="dot"></span>条件路由
</div>
```

4. `static/editor.js` 属性面板 `renderProps`（约 L388-449）：当 `n.type === 'router'` 时渲染分支编辑器（在现有输出字段编辑区之前插入）：

```js
    if (n.type === 'router') {
      html += `<label>候选分支（模型从中选择一个）</label><div id="route-list">`;
      (n.routes || []).forEach((r, i) => {
        html += `<div class="route-row">
          <input data-route-i="${i}" data-route-k="label" value="${esc(r.label || '')}" placeholder="分支名">
          <input data-route-i="${i}" data-route-k="desc" value="${esc(r.desc || '')}" placeholder="说明（可选）">
          <button class="btn-mini" data-del-route="${i}">×</button>
        </div>`;
      });
      html += `</div><button id="add-route" class="btn-mini" type="button">+ 添加分支</button>`;
    }
```

对应事件绑定（renderProps 事件区追加）：

```js
    panel.querySelectorAll('[data-route-i]').forEach(inp => inp.onchange = () => {
      const i = +inp.dataset.routeI, k = inp.dataset.routeK;
      (n.routes = n.routes || [])[i] = n.routes[i] || {};
      n.routes[i][k] = inp.value;
      markDirty(); renderWires();
    });
    const addRoute = panel.querySelector('#add-route');
    if (addRoute) addRoute.onclick = () => { (n.routes = n.routes || []).push({ label: '', desc: '' }); renderProps(); };
    panel.querySelectorAll('[data-del-route]').forEach(b => b.onclick = () => {
      n.routes.splice(+b.dataset.delRoute, 1); renderProps(); renderWires();
    });
```

5. `editor.js` 边选中属性（wires click 约 L183-196 → renderProps）：当所选边的 `from` 节点是 router 时，追加分支条件下拉：

```js
    const fromNode = state.flow.nodes.find(n => n.id === edge.from);
    if (fromNode && fromNode.type === 'router') {
      const opts = ['<option value="">默认边（未命中时走）</option>'].concat((fromNode.routes || []).map(r =>
        `<option value="${esc(r.label)}" ${edge.condition === r.label ? 'selected' : ''}>${esc(r.label)}</option>`));
      html += `<label>分支条件</label><select id="edge-condition">${opts.join('')}</select>`;
    }
```

```js
    const cond = panel.querySelector('#edge-condition');
    if (cond) cond.onchange = () => { edge.condition = cond.value || null; markDirty(); renderWires(); };
```

（边属性渲染入口：若现有 renderProps 只处理节点，需为选中边状态补一个轻量边面板；实施时以现有 wires click 的确认删除交互为基础扩展。）

6. `editor.js` `renderWires`（约 L167-180）：为有 condition 的边在 hit-path 旁渲染文字标签（可选增强，实现方式：SVG `<text>` 置于边中点，内容 = condition）。

### Step 2.7：手动验证（mock）

1. `flow.ai.provider=mock` 启动，编辑器新建流程：`输入文本 → 条件路由（分支A/分支B）→ A下游文本节点 / B下游JSON节点`。
2. 运行后观察运行页：命中分支的下游正常 SUCCESS；未命中分支显示 CANCELLED（「未命中路由分支…已取消」）。
3. 回归验证既有示例流程（「合同审查」等）不受影响（边默认 condition=null，非 ROUTER 节点恒激活）。

**Commit**

```
git add -A && git commit -m "feat(flow): A1 条件分支节点——ROUTER路由+边条件+引擎调度+前端编辑"
```

---

## Task 3: E2 重试/降级（瞬时错误指数退避 + fallback 模型兜底）

**目标**：`SpringAiInvoker.invoke()` 遇瞬时错误（限流/超时/连接重置等）自动指数退避重试；重试耗尽后用 `fallbackModel` 兜底一次；全部失败抛原异常（suppressed 附加降级失败原因）。

**定稿语义**

- 瞬时错误启发式：异常消息小写包含 `429 / 500 / 502 / 503 / 504 / timeout / timed out / connection / reset / overloaded` 之一。
- 退避：`min(8000, retryBackoffMillis * 2^(n-1))`。
- `stream()` 方法**不动**（流式不重试，避免消费端收到重复片段）。

**Files**

- Modify: `src/main/java/com/guonl/flow/config/AiProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/guonl/flow/ai/SpringAiInvoker.java`

### Step 3.1：配置项

`AiProperties` 追加（import 无新增）：

```java
    /** 瞬时错误（限流/超时/连接抖动）自动重试次数 */
    private int invokeRetryAttempts = 2;
    /** 重试基础退避毫秒，按 2^(n-1) 指数递增，上限 8 秒 */
    private long retryBackoffMillis = 500;
    /** 主模型重试耗尽后的降级模型名，留空禁用 */
    private String fallbackModel = "";
```

`application.yml` 的 `flow.ai:` 段追加：

```yaml
      # 瞬时错误重试与降级
      invoke-retry-attempts: 2
      retry-backoff-millis: 500
      fallback-model: ""
```

### Step 3.2：SpringAiInvoker 重构 invoke

> 实施前先 Read `SpringAiInvoker.java`（约 224 行）：现有 `invoke()` 约 L54-95、`describeError()` L139-154、`buildOptions()` L157-164、`resolveModel()` L180-185。

1. 现有 `invoke()` 方法体整体抽为私有 `doInvoke(InvokeRequest request, String modelOverride)`：逻辑与原 `invoke()` 一致（ChatClient 调用、空内容抛 `IllegalStateException`、catch 后 `describeError` 包装），唯一差异是模型解析处改为：

```java
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride : resolveModel(request.getModel());
```

2. 新的 `invoke()`（`@Override` 保留在此方法）：

```java
    @Override
    public InvokeResult invoke(InvokeRequest request) {
        int maxAttempts = 1 + Math.max(0, aiProperties.getInvokeRetryAttempts());
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doInvoke(request, null);
            } catch (IllegalStateException e) {
                last = e;
                if (attempt >= maxAttempts || !isTransient(e)) {
                    break;
                }
                long backoff = Math.min(8000, aiProperties.getRetryBackoffMillis() * (1L << (attempt - 1)));
                log.warn("[Invoker] {} transient error (attempt {}/{}), retry in {}ms: {}",
                        request.getBizKey(), attempt, maxAttempts, backoff, e.getMessage());
                sleepQuietly(backoff);
            }
        }
        // 主模型重试耗尽：降级模型兜底一次
        String fb = aiProperties.getFallbackModel();
        if (fb != null && !fb.isBlank()) {
            try {
                log.warn("[Invoker] {} retries exhausted, falling back to model: {}", request.getBizKey(), fb);
                return doInvoke(request, fb);
            } catch (Exception e) {
                last.addSuppressed(e);
            }
        }
        throw last;
    }

    /** 瞬时错误启发式判断 */
    private boolean isTransient(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("500") || msg.contains("502") || msg.contains("503")
                || msg.contains("504") || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("connection") || msg.contains("reset") || msg.contains("overloaded");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
```

3. `stream()` 保持原样不动。

**Run**

```
mvn -q compile
```

期望：BUILD SUCCESS。

手动验证：mock provider 不触发真实异常，重点验证回归（正常流程运行无变化）；观察日志无 `[Invoker] ... transient error` 即重试路径未被误触发。若需真实触发可临时把 `text-model` 改为不存在的模型名，观察 `IllegalStateException`（非瞬时消息）直接抛出、不进入退避重试。

**Commit**

```
git add -A && git commit -m "feat(flow): E2 模型调用重试降级——瞬时错误指数退避+fallback模型兜底"
```

---

## Task 4: F5 运行页流式（SSE 推送节点增量输出）

**目标**：节点执行期间把模型增量输出经 SSE 实时推到运行页，消除「长时间转圈无反馈」。

**定稿语义**

- `InvokeRequest` 加 `Consumer<String> onDelta`；`NodeContext` 加第 5 参 `streamSink`（`Consumer<String>`，仅 delta，nodeId 由 FlowEngine 闭包捕获）。
- `SpringAiInvoker`：`invoke()` 检测 `onDelta != null` 时内部走流式路径聚合返回（**不走 E2 重试**，避免重复推送 delta）。
- SSE 用默认 message 事件，payload 为 JSON：`{"nodeId":"...","delta":"..."}`；结束时发 `{"done":true}` 并关闭。
- 前端 `streamBuf` 缓冲增量，解决「轮询 tick 覆盖流式内容导致闪烁」。

**Files**

- Modify: `src/main/java/com/guonl/flow/ai/AiInvoker.java`
- Modify: `src/main/java/com/guonl/flow/core/node/NodeContext.java`
- Modify: `src/main/java/com/guonl/flow/core/engine/FlowEngine.java`
- Create: `src/main/java/com/guonl/flow/core/engine/RunStreamHub.java`
- Modify: `src/main/java/com/guonl/flow/web/RunApiController.java`
- Modify: `src/main/java/com/guonl/flow/ai/SpringAiInvoker.java`、`MockAiInvoker.java`
- Modify: `src/main/java/com/guonl/flow/core/node/AbstractNodeHandler.java`
- Modify: `src/main/resources/static/run.js`

### Step 4.1：SPI 与上下文

1. `AiInvoker.InvokeRequest`（`@Builder` 内部类）追加字段（`java.util.function.Consumer` 文件头已 import，零成本）：

```java
        /** 增量输出回调；非空时实现方可走流式路径 */
        private Consumer<String> onDelta;
```

2. `NodeContext`（`@Getter @RequiredArgsConstructor`，现 4 字段）追加第 5 参：

```java
    /** 节点级增量输出 sink（运行期由引擎注入，可为 null） */
    private final java.util.function.Consumer<String> streamSink;
```

（构造点仅 `FlowEngine:261` 一处，见 Step 4.3。）

### Step 4.2：RunStreamHub（新建）

```java
package com.guonl.flow.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行页流式推送中心：runId → SSE 连接列表。
 */
@Component
public class RunStreamHub {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由 complete 主动关闭
        emitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> {
            CopyOnWriteArrayList<SseEmitter> list = emitters.get(runId);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(String runId, String nodeId, String delta) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(runId);
        if (list == null || list.isEmpty() || delta == null || delta.isEmpty()) return;
        String payload;
        try {
            payload = MAPPER.writeValueAsString(Map.of("nodeId", nodeId, "delta", delta));
        } catch (Exception e) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().data(payload));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    /** 运行结束：广播 done 并关闭全部连接 */
    public void complete(String runId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.remove(runId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().data("{\"done\":true}"));
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已断开，忽略
            }
        }
    }
}
```

### Step 4.3：FlowEngine 注入 sink 与收尾

1. 字段追加（`@RequiredArgsConstructor` 自动注入）：`private final RunStreamHub streamHub;`
2. `submit()` 中构建 `NodeContext` 前（原 L261 处）追加 sink 并改 5 参构造：

```java
        java.util.function.Consumer<String> sink = delta -> streamHub.publish(execution.getRunId(), nodeId, delta);
        NodeContext ctx = new NodeContext(execution.getInput(), upstreamOutputs, upstreamNames, ancestorIds, sink);
```

3. `run()` 方法体（从「构建 downstream」到「终态落库」）包 try/finally，结束时广播 done：

```java
        try {
            // …… run() 原有调度逻辑 ……
        } finally {
            streamHub.complete(execution.getRunId());
        }
```

### Step 4.4：SSE 端点

`RunApiController`（`@RequestMapping("/api/runs")`）追加依赖与方法（构造注入方式与现有字段保持一致）：

```java
    private final RunStreamHub streamHub;

    /** 订阅指定运行的实时流 */
    @GetMapping(value = "/{runId}/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@PathVariable String runId) {
        return streamHub.subscribe(runId);
    }
```

（import 规范化后 SseEmitter/MediaType/RunStreamHub 走正常 import。）

### Step 4.5：两个 Invoker 支持 onDelta

1. `SpringAiInvoker`：把现有覆写 `stream()` 的主体抽为私有 `String doStream(InvokeRequest request, java.util.function.Consumer<String> onChunk)`（订阅 Flux、逐 chunk 回调、返回聚合全文；usage 聚合按现有 stream 实现能力处理，拿不到就置 null）；public `stream()` 委托 `doStream`。`invoke()` 开头加流式分支：

```java
    @Override
    public InvokeResult invoke(InvokeRequest request) {
        if (request.getOnDelta() != null) {
            return streamInvoke(request); // 流式路径不走重试，避免 delta 重复推送
        }
        // …… E2 重试循环原样 ……
    }

    private InvokeResult streamInvoke(InvokeRequest request) {
        InvokeResult result = new InvokeResult();
        result.setContent(doStream(request, request.getOnDelta()));
        result.setModel(resolveModel(request.getModel()));
        return result;
    }
```

2. `MockAiInvoker.invoke()`：生成最终文本后、返回前追加打字机回调：

```java
        if (request.getOnDelta() != null && content != null && !content.isEmpty()) {
            for (int i = 0; i < content.length(); i += 6) {
                request.getOnDelta().accept(content.substring(i, Math.min(i + 6, content.length())));
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
```

3. `AbstractNodeHandler` 纠错循环中的 `InvokeRequest.builder()` 链追加：`.onDelta(ctx.getStreamSink())`。

### Step 4.6：前端 run.js

1. 顶部状态加 `const streamBuf = {};`（runId 变化/重新运行时清空）。
2. 页面加载或查看运行时订阅：

```js
  const es = new EventSource(`/api/runs/${currentRunId}/stream`);
  es.onmessage = ev => {
    let p; try { p = JSON.parse(ev.data); } catch (e) { return; }
    if (p.done) { es.close(); return; }
    streamBuf[p.nodeId] = (streamBuf[p.nodeId] || '') + p.delta;
    renderStreaming(p.nodeId);
  };
  es.onerror = () => { /* EventSource 自动重连；运行已结束的 404 交给轮询兜底 */ };
```

3. `renderStreaming(nodeId)`：定位该节点卡片，若仍为运行中状态，把 `streamBuf[nodeId]` 渲染进输出区（追加/替换皆可，注意 `esc()`）。
4. 轮询 `tick()` 渲染节点详情时：节点 RUNNING 且 `streamBuf[nodeId]` 非空 → 优先显示流式缓冲；节点到 SUCCESS/FAILED 终态 → 使用轮询数据并 `delete streamBuf[nodeId]`（消除闪烁的关键：不要每 tick 用空输出覆盖流式内容）。

### Step 4.7：手动验证（mock）

1. `flow.ai.provider=mock` 启动，运行页触发一次运行：节点执行期间输出区逐字打字机式增长，完成后为最终完整输出。
2. 打开两个浏览器标签页订阅同一 run，均能收到增量。
3. 运行结束后 SSE 收到 `{"done":true}` 并关闭；轮询与流式无缝衔接、无闪烁回跳。

**Commit**

```
git add -A && git commit -m "feat(flow): F5 运行页流式——SSE增量推送+前端streamBuf缓冲"
```

---

## Phase 2-4 概要（开工前按 Phase 1 同样格式细化）

### Phase 2：工具调用 + 记忆 + 聚合

- **B1 工具调用节点**：新增 `TOOL` 节点类型；基于 Spring AI `@Tool` 注解注册内置工具集（当前时间、HTTP 抓取、JSONPath 提取、数学计算），ChatClient `.tools()` 交给模型自主调用；节点输出包含工具调用轨迹（`ToolCallingChatOptions` 的执行记录），前端运行页展示「调用了什么工具、传了什么参数、返回了什么」。
- **D1 JDBC 会话记忆**：引入 `spring-ai-starter-model-chat-memory-repository-jdbc`，`MessageChatMemoryAdvisor` 挂到 ChatClient；记忆窗口按「会话 ID」（运行参数传入）隔离；节点定义加 `memoryTurns` 可选配置（0=无记忆），多轮流程（如追问、润色链）自动携带历史。
- **A2 聚合节点**：新增 `AGGREGATE` 节点类型（不经模型）：多上游输出合并策略——拼接 / JSON 字段合并 / 模板渲染（支持 `{{节点名}}` 变量），解决汇流点只能引用单个上游产出的痛点。

### Phase 3：RAG 知识库 + MCP Server + 多模态扩展

- **C1 知识库管理**：文档上传（txt/md/pdf）→ Spring AI ETL 管道（`TokenTextSplitter` 切分 + `EmbeddingModel` 向量化）→ 向量存储（起步用 `SimpleVectorStore` 落盘，预留 pgvector/Redis 实现位）；知识库 CRUD 页面。
- **C2 检索增强节点**：节点定义加 `knowledgeBaseId` 可选引用，经 `RetrievalAugmentationAdvisor` 自动检索注入上下文；运行页展示命中片段。
- **B2 MCP Server**：把已有流程包装为 MCP tool 对外暴露（`spring-ai-starter-mcp-server-webmvc`），流程输入 → tool 参数 schema 自动映射，外部 AI 客户端可直接调用本产品的流程能力。
- **F1/F3/F4 多模态扩展**：F1 语音输入（audio transcription 兼容层，运行输入加音频）；F3 文生图节点（image model，输出图片 URL 入 NodeOutput）；F4 输出审核节点（敏感内容检测，命中则走降级分支）。

### Phase 4：评估 + 观测 + 编排进阶

- **E3 输出评估**：LLM-as-judge 节点——对指定上游输出按评估维度（准确性/完整性/格式）打分并给出理由，低分可联动 A1 条件分支走重试或人工兜底。
- **F6 可观测**：接入 Spring AI Observability + Micrometer，运行列表页增加 token 用量、耗时、调用次数统计面板。
- **A3 子流程节点**：节点引用另一个流程定义作为「宏」，入参/出参映射，支持流程复用。
- **B3 MCP 客户端**：接入外部 MCP server 作为 B1 工具节点的工具来源（`spring-ai-starter-mcp-client`），工具清单动态发现。

---

## 执行注意事项

1. **顺序执行**：Task 1 → 2 → 3 → 4，每个任务独立 commit，编译不过不进入下一任务。
2. **先读后改**：每个文件修改前先 Read（本文档行号为快照）；`editor.js`、`run.js`、`SpringAiInvoker`、`MockAiInvoker` 仅做过区段核对，实施时以实际代码为准。
3. **交叉衔接点**（已按依赖设计，勿倒置）：
   - A1 的 RouterNodeHandler 依赖 E1 的纠错循环（无效 route 自纠）——Task 1 先行；
   - F5 的流式分支绕过 E2 重试（防 delta 重复）——Task 3 先行；
   - F5 的 `AbstractNodeHandler` 改动叠加在 E1 的纠错循环代码之上。
4. **回归底线**：每任务完成后跑一遍既有示例流程（mock），确认无回归再 commit。
5. **Phase 2-4**：按需细化后执行，格式与本Phase 1 一致（writing-plans 规范）。

