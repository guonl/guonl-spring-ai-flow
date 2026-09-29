package com.guonl.flow.assistant;

import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.core.store.FlowStore;
import com.guonl.flow.core.store.RunStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mock 模式助手应答器：意图路由（关键词分类）+ 真实数据查询 + 模板化回答。
 * <p>无大模型也能演示「查流程/看运行/诊断」等真实能力——数据全部来自 FlowStore/RunStore，
 * 仅自然语言理解退化为关键词匹配。问答知识（节点/变量协议/FAQ）内置速查表。</p>
 */
@Component
@RequiredArgsConstructor
public class MockAssistantResponder {

    /** 运行ID模式（r_ 前缀） */
    private static final Pattern RUN_ID = Pattern.compile("r_[A-Za-z0-9\\-]+");

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final FlowStore flowStore;

    private final RunStore runStore;

    /** 意图路由：按优先级匹配，返回 markdown 回答 */
    public String reply(String message) {
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            return fallback();
        }
        try {
            // 1. 诊断运行：提到诊断类词 或 消息里带 runId
            Matcher runId = RUN_ID.matcher(msg);
            if (runId.find() || containsAny(msg, "诊断", "排查", "为什么失败", "咋失败")) {
                return diagnose(msg, runId);
            }
            // 2. 流程列表
            if (containsAny(msg, "流程列表", "有哪些流程", "所有流程", "流程清单", "查流程", "列出流程")) {
                return listFlows(extractKeywordAfter(msg, "流程"));
            }
            // 3. 流程详情
            if (containsAny(msg, "流程详情", "看看流程", "流程定义", "节点结构")) {
                return flowDetail(extractFlowName(msg));
            }
            // 4. 运行历史
            if (containsAny(msg, "最近运行", "运行历史", "运行记录", "历史运行")) {
                return recentRuns();
            }
            // 5. 平台统计
            if (containsAny(msg, "统计", "成功率", "运行多少次")) {
                return stats();
            }
            // 6. 建流程引导
            if (containsAny(msg, "帮我建", "生成流程", "创建流程", "新建流程", "做一个流程")) {
                return buildFlowGuide();
            }
            // 7. 产品知识问答
            String qa = answerKnowledge(msg);
            if (qa != null) {
                return qa;
            }
            // 8. 兜底
            return fallback();
        } catch (IllegalArgumentException e) {
            return "查询失败：" + e.getMessage();
        } catch (Exception e) {
            return "mock 助手处理出错：" + e.getMessage();
        }
    }

    // ---------- 意图实现 ----------

    private String diagnose(String msg, Matcher runId) {
        String id = runId.find() ? runId.group() : extractTrailingToken(msg);
        if (!StringUtils.hasText(id)) {
            return """
                请提供要诊断的运行ID（在「运行历史」页可复制，形如 `r_xxxx`），例如：

                > 帮我诊断 r_1948f2a1

                mock 助手会拉取该次运行的节点执行记录，按「输入 → 节点 → 模型」逐层归因。""";
        }
        FlowExecution run = runStore.get(id);
        if (run == null) {
            return "没有找到运行 `" + id + "`，请确认运行ID是否正确（可在「运行历史」页查询）。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 运行诊断：").append(run.getFlowName()).append("\n\n");
        sb.append("- 运行ID：`").append(run.getRunId()).append("`\n");
        sb.append("- 状态：**").append(run.getStatus()).append("**");
        if (run.getEndAt() > 0) {
            sb.append("，耗时 ").append(fmtCost(run.getCostMillis()));
        }
        sb.append("\n");
        sb.append("- 节点：共 ").append(run.getNodes().size()).append(" 个，成功 ")
            .append(run.getSuccessCount()).append(" 个，tokens ").append(run.getTotalTokens()).append("\n\n");
        if (run.getStatus() == FlowExecution.Status.RUNNING) {
            sb.append("该运行仍在执行中，暂无失败信息可诊断。可在「运行历史」页关注其进展。\n");
            return sb.toString();
        }
        if (run.getStatus() == FlowExecution.Status.SUCCESS) {
            sb.append("该运行全部成功，无需诊断。如需优化输出质量，可以把节点提示词发给我帮你改进。\n");
            return sb.toString();
        }
        sb.append("### 失败节点\n\n");
        for (NodeExecution ne : run.getNodes().values()) {
            if (ne.getStatus() == NodeExecution.Status.FAILED && StringUtils.hasText(ne.getError())) {
                sb.append("- **").append(ne.getNodeName()).append("**（")
                    .append(ne.getNodeType() == null ? "-" : ne.getNodeType().getLabel()).append("）：")
                    .append(abbreviate(ne.getError(), 160)).append("\n");
            }
        }
        sb.append("\n### 归因建议\n\n");
        sb.append(attribute(run));
        return sb.toString();
    }

    /** 简易归因：对失败节点的错误信息做启发式分类 */
    private String attribute(FlowExecution run) {
        for (NodeExecution ne : run.getNodes().values()) {
            if (ne.getStatus() != NodeExecution.Status.FAILED || !StringUtils.hasText(ne.getError())) {
                continue;
            }
            String err = ne.getError().toLowerCase();
            if (err.contains("429") || err.contains("rate") || err.contains("限流")) {
                return "- 错误含 429/限流：模型网关限流，稍后重跑即可；高频场景可在流程里降低并发或为节点配置重试。\n";
            }
            if (err.contains("timeout") || err.contains("timed out") || err.contains("超时")) {
                return "- 错误含超时：模型响应过慢或网络抖动，可重跑；持续出现建议换更快的模型或调大 node-timeout-seconds。\n";
            }
            if (err.contains("json") || err.contains("parse") || err.contains("解析")) {
                return "- JSON 解析失败：多为模型输出不符合 outputSpec 契约。建议：① 明确 prompt 中要求仅输出 JSON；② 降低节点温度（0.3 以下）；③ 检查契约字段与提示词示例一致。\n";
            }
            if (err.contains("{{") || err.contains("variable") || err.contains("变量")) {
                return "- 变量引用问题：检查 prompt 中 `{{nodeId}}` 是否为存在的上游节点ID，Excel 需用 `{{input.rows}}`。\n";
            }
            if (err.contains("401") || err.contains("403") || err.contains("key")) {
                return "- 鉴权失败（401/403）：检查 OPENAI_API_KEY 与网关地址配置。\n";
            }
        }
        return "- 未命中已知错误模式：建议复制运行页的失败节点错误信息，切换 llm 模式的助手做深入分析。\n";
    }

    private String listFlows(String keyword) {
        PageResult<FlowDefinition> page = flowStore.search(keyword, 1, 20);
        List<FlowDefinition> flows = page.items();
        if (flows.isEmpty()) {
            return keyword == null
                ? "当前还没有流程。可以在「流程库」新建，或对我说「帮我建一个流程」。\n"
                : "没有找到与「" + keyword + "」相关的流程。试试换个关键词，或到「流程库」检索。\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前共有 **").append(page.total()).append("** 个流程")
            .append(keyword != null ? "（关键词：" + keyword + "）" : "").append("：\n\n");
        sb.append("| # | 流程名 | 节点数 | 更新时间 |\n|---|--------|--------|----------|\n");
        int i = 1;
        for (FlowDefinition f : flows) {
            sb.append("| ").append(i++).append(" | ").append(f.getName())
                .append(" | ").append(f.getNodes().size())
                .append(" | ").append(fmtTime(f.getUpdatedAt())).append(" |\n");
        }
        sb.append("\n想看某个流程的节点结构，回复「流程详情 + 名称」即可。");
        return sb.toString();
    }

    private String flowDetail(String name) {
        if (!StringUtils.hasText(name)) {
            return "请告诉我流程名称或ID，例如：\n\n> 看看流程 Excel摘要\n\n也可以先问「有哪些流程」。";
        }
        FlowDefinition flow = findFlowByName(name);
        if (flow == null) {
            return "没有找到名为「" + name + "」的流程，可以先问「有哪些流程」查看清单。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 流程：").append(flow.getName()).append("\n\n");
        if (StringUtils.hasText(flow.getDescription())) {
            sb.append(flow.getDescription()).append("\n\n");
        }
        sb.append("### 节点（").append(flow.getNodes().size()).append("）\n\n");
        int i = 1;
        for (NodeDefinition n : flow.getNodes()) {
            sb.append(i++).append(". **").append(n.getName()).append("**（").append(n.getType().getLabel())
                .append("，`").append(n.getId()).append("`）");
            if (StringUtils.hasText(n.getPrompt())) {
                sb.append("：").append(abbreviate(n.getPrompt().replaceAll("\\s+", " "), 60));
            }
            sb.append("\n");
        }
        if (!flow.getEdges().isEmpty()) {
            sb.append("\n### 连线\n\n");
            for (var e : flow.getEdges()) {
                sb.append("- `").append(e.getFrom()).append("` → `").append(e.getTo()).append("`");
                if (StringUtils.hasText(e.getCondition())) {
                    sb.append("（条件：").append(e.getCondition()).append("）");
                }
                sb.append("\n");
            }
        }
        sb.append("\n可在 [流程编辑器](/flow-editor?flowId=").append(flow.getId()).append(") 中打开编辑。");
        return sb.toString();
    }

    private String recentRuns() {
        List<FlowExecution> runs = runStore.list();
        if (runs.isEmpty()) {
            return "还没有运行记录。到「场景体验」提交一次流程试试。\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### 最近运行（最多10条）\n\n");
        sb.append("| 运行ID | 流程 | 状态 | 耗时 |\n|--------|------|------|------|\n");
        int i = 0;
        for (FlowExecution r : runs) {
            if (i++ >= 10) {
                break;
            }
            sb.append("| `").append(r.getRunId()).append("` | ").append(r.getFlowName())
                .append(" | ").append(statusEmoji(r.getStatus().name())).append(" ")
                .append(r.getStatus()).append(" | ")
                .append(r.getEndAt() > 0 ? fmtCost(r.getCostMillis()) : "-").append(" |\n");
        }
        sb.append("\n把运行ID发给我（形如 `r_xxxx`）可做失败诊断。");
        return sb.toString();
    }

    private String stats() {
        RunStore.Stats s = runStore.stats();
        return """
            ### 平台运行统计

            | 指标 | 数值 |
            |------|------|
            | 总运行数 | %d |
            | 成功 / 失败 | %d / %d |
            | 成功率 | %s%% |
            | 平均耗时 | %s |
            | 累计 tokens | %d |

            数据来自 flow_run 表实时聚合。
            """.formatted(s.getTotal(), s.getSuccess(), s.getFailed(), s.getSuccessRate(), fmtCost(s.getAvgCostMillis()), s.getTotalTokens());
    }

    private String buildFlowGuide() {
        return """
            ### 自然语言建流程

            当前为 **mock 模式**，为避免无模型时产生脏数据，建流程能力需切换 llm 模式使用：

            1. 设置环境变量 `FLOW_AI_PROVIDER=llm` 后重启
            2. 对我说需求，例如：

            > 帮我建一个流程：Excel 输入 → 提取关键字段为 JSON → 总结成文本

            3. 我会先给出节点方案与你确认，确认后保存并在画布打开。

            你也可以先到 [流程编辑器](/flow-editor) 手动拖拽体验。
            """;
    }

    // ---------- 产品知识速查 ----------

    private String answerKnowledge(String msg) {
        String lower = msg.toLowerCase();
        if (containsAny(msg, "变量", "{{")) {
            return """
                ### 变量协议速查

                | 写法 | 含义 |
                |------|------|
                | `{{input}}` | 流程输入文本 |
                | `{{input.rows}}` | Excel 解析结果 |
                | `{{nodeId}}` / `{{nodeId.output}}` | 上游节点原始输出 |
                | `{{nodeId.json}}` | 上游节点 JSON |
                | `{{nodeId.json.字段}}` | 上游 JSON 的字段值 |

                示例：`请把以下数据翻译为英文：{{n_1712.json.result}}`
                """;
        }
        if (containsAny(msg, "条件路由", "router", "路由节点")) {
            return """
                ### 条件路由（ROUTER）

                - 在节点上配置分支：`routes: [{label, desc}]`，desc 帮助模型判断走哪个分支
                - 模型输出 `{"route":"分支名"}` 决定走向
                - 下游边设置 condition=分支名；留空的边为默认边（无条件边命中时激活）
                """;
        }
        if (containsAny(msg, "知识检索", "knowledge", "rag", "向量")) {
            return """
                ### 知识检索（RAG）

                - 先在「知识库」页创建库并上传文档（状态需为 READY，即向量化完成）
                - 流程中加 KNOWLEDGE 节点，绑定 knowledgeBaseId，topK 控制命中片段数
                - 模型基于检索片段作答，减少幻觉
                """;
        }
        if (containsAny(msg, "聚合", "aggregate", "汇流")) {
            return """
                ### 聚合（AGGREGATE）

                多上游输出的汇合点（不经模型），三种策略：
                - **concat**：按序拼接（prompt 复用为分隔符）
                - **jsonMerge**：JSON 字段合并（字段名取节点名）
                - **template**：模板渲染（prompt 复用为模板，`{{节点ID}}` 引用各上游）
                """;
        }
        if (containsAny(msg, "子流程", "subflow")) {
            return """
                ### 子流程（SUBFLOW）

                引用另一个已保存流程作为「宏」同步执行：入参经 prompt 模板组装，出参取子流程末端节点输出，适合复用通用步骤（如统一摘要、统一审核）。
                """;
        }
        if (containsAny(msg, "mcp")) {
            return """
                ### MCP 接入

                - 平台作为 **MCP Server**：把已保存流程暴露为 tools，端点 `/guonl/ai/mcp`（STATELESS/SYNC）
                - 外部客户端（Claude/Cursor 等）配置该地址即可直接调用流程
                - 平台也可作为 **MCP Client**：`MCP_CLIENT_ENABLED=true` 连接外部 server，发现的工具供 TOOL 节点使用
                """;
        }
        if (containsAny(msg, "running", "一直运行", "卡住")) {
            return """
                ### 运行一直 RUNNING？

                1. provider=llm 时确认模型网关可用（看启动日志有无报错）
                2. 节点默认超时 180s（flow.ai.node-timeout-seconds），超时后会失败而非永久卡住
                3. 到「运行历史」看节点粒度状态，定位停在哪个节点
                """;
        }
        if (containsAny(msg, "节点", "node") && containsAny(msg, "类型", "有哪些", "介绍", "说明")) {
            return """
                ### 13 种节点速查

                | 类型 | 用途 |
                |------|------|
                | TEXT 文本理解 | 纯文字输入处理 |
                | IMAGE 图片识别 | 图片输入识别 |
                | JSON / EXCEL | 结构化数据处理 |
                | COMBINED 组合 | 多源输入组合 |
                | ROUTER 条件路由 | 预定义分支选路 |
                | TOOL 工具调用 | 时间/计算/抓取/JSON提取 |
                | KNOWLEDGE 知识检索 | RAG 向量检索 |
                | IMAGE_GEN 图片生成 | 文生图 |
                | MODERATION 内容审核 | 违规检测 |
                | EVALUATE 输出评估 | LLM-as-judge 打分 |
                | SUBFLOW 子流程 | 流程复用为宏 |
                | AGGREGATE 聚合 | 多上游汇合（不经模型） |
                """;
        }
        return null;
    }

    private String fallback() {
        return """
            你好，我是平台助手「小流」（当前 mock 模式，回复基于关键词匹配 + 真实数据查询）。可以这样问我：

            - **查流程**：「有哪些流程」「看看流程 Excel摘要」
            - **运行**：「最近运行」「平台统计」
            - **诊断**：「帮我诊断 r_xxxx」
            - **答疑**：「变量协议怎么写」「条件路由怎么配」「什么是聚合节点」「MCP 怎么接入」

            切换 llm 模式（`FLOW_AI_PROVIDER=llm`）后，我可支持自然语言建流程、提示词优化与更自然的对话。
            """;
    }

    // ---------- 工具方法 ----------

    private boolean containsAny(String text, String... keys) {
        String lower = text.toLowerCase();
        for (String k : keys) {
            if (lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 提取「流程」后面的检索词（如「查流程 翻译」→ 翻译）；无则 null 全量 */
    private String extractKeywordAfter(String msg, String anchor) {
        int idx = msg.lastIndexOf(anchor);
        if (idx >= 0 && idx + anchor.length() < msg.length()) {
            String rest = msg.substring(idx + anchor.length()).trim().replaceAll("[。？！?!，,\\s]+$", "");
            if (StringUtils.hasText(rest)) {
                return rest;
            }
        }
        return null;
    }

    /** 流程详情：取「流程/看看/查看」后面的名称 */
    private String extractFlowName(String msg) {
        for (String anchor : new String[]{"流程详情", "看看流程", "查看流程", "流程"}) {
            String name = extractKeywordAfter(msg, anchor);
            if (StringUtils.hasText(name)) {
                return name.replaceAll("[。？！?!，,\\s]+$", "");
            }
        }
        return null;
    }

    private FlowDefinition findFlowByName(String name) {
        if (name.startsWith("f_")) {
            try {
                return flowStore.require(name);
            } catch (Exception ignored) {
                // 走名称匹配
            }
        }
        return flowStore.list().stream()
            .filter(f -> f.getName().contains(name) || name.contains(f.getName()))
            .findFirst().orElse(null);
    }

    /** 兜底提取消息末尾的 token（用户只发 runId 的场景） */
    private String extractTrailingToken(String msg) {
        String[] parts = msg.trim().split("\\s+");
        String last = parts[parts.length - 1];
        return last.matches("[A-Za-z0-9_\\-]{4,64}") ? last : null;
    }

    private String statusEmoji(String status) {
        return switch (status) {
            case "SUCCESS" -> "✅";
            case "FAILED" -> "❌";
            case "RUNNING" -> "⏳";
            default -> "·";
        };
    }

    private String fmtCost(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000.0);
    }

    private String fmtTime(java.time.LocalDateTime time) {
        return time == null ? "-" : time.format(TIME_FMT);
    }

    private String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
