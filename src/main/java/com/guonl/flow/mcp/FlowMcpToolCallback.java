package com.guonl.flow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.FlowRunService;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.store.RunStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.stream.Collectors;

/**
 * 单个流程的MCP tool视图：tool名=流程ID，入参schema按流程输入映射，
 * call() 同步执行流程并等待终态，返回各节点输出汇总。
 */
@Slf4j
public class FlowMcpToolCallback implements ToolCallback {

    /** MCP协议对tool名的合法字符约束（字母数字下划线连字符，1-64） */
    private static final String NAME_PATTERN = "[^a-zA-Z0-9_-]";

    /** tool入参JSON解析器（ObjectMapper线程安全；独立实例避免依赖自动装配） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowDefinition flow;

    private final FlowRunService flowRunService;

    private final RunStore runStore;

    private final long waitTimeoutMs;

    public FlowMcpToolCallback(FlowDefinition flow, FlowRunService flowRunService, RunStore runStore,
                               long waitTimeoutMs) {
        this.flow = flow;
        this.flowRunService = flowRunService;
        this.runStore = runStore;
        this.waitTimeoutMs = waitTimeoutMs;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String desc = flow.getName();
        if (flow.getDescription() != null && !flow.getDescription().isBlank()) {
            desc = desc + "：" + flow.getDescription();
        }
        // 避免与流程自带描述的结尾标点重复
        if (!desc.matches(".*[。.!！?？]$")) {
            desc = desc + "。";
        }
        desc = desc + "调用后同步执行该AI流程并返回各节点输出。";
        return DefaultToolDefinition.builder()
            .name(flow.getId().replaceAll(NAME_PATTERN, "_"))
            .description(desc)
            .inputSchema("""
                {"type":"object","properties":{
                  "text":{"type":"string","description":"流程文本输入（普通文本或JSON字符串，即流程变量 input）"},
                  "conversationId":{"type":"string","description":"可选会话ID；携带后该流程的对话记忆节点将携带同会话历史"}
                },"required":["text"]}""")
            .build();
    }

    @Override
    public String call(String toolInput) {
        String text;
        String conversationId;
        try {
            JsonNode in = MAPPER.readTree(toolInput);
            text = in.path("text").asText(null);
            conversationId = in.path("conversationId").asText(null);
        } catch (Exception e) {
            throw new IllegalArgumentException("tool入参必须是JSON对象：{\"text\":\"...\"}", e);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 text（流程文本输入）");
        }
        FlowInput input = FlowInput.builder()
            .text(text)
            .conversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null)
            .build();

        // 提交运行并轮询终态（MCP tool调用为同步语义）
        FlowExecution execution = flowRunService.start(flow, input);
        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        try {
            while (!execution.isFinished()) {
                if (System.currentTimeMillis() > deadline) {
                    return "流程仍在执行中，已超过同步等待上限（" + waitTimeoutMs / 1000 + "秒）。"
                        + "runId=" + execution.getRunId() + "，可在流程产品的运行记录页查看最终结果。";
                }
                Thread.sleep(300);
                FlowExecution latest = runStore.get(execution.getRunId());
                if (latest != null) {
                    execution = latest;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待流程执行被中断：runId=" + execution.getRunId(), e);
        }
        return summarize(execution);
    }

    /** 终态结果汇总：成功按拓扑序列出各节点输出；失败列出错误节点与原因 */
    private String summarize(FlowExecution execution) {
        StringBuilder sb = new StringBuilder();
        if (execution.getStatus() == FlowExecution.Status.FAILED) {
            sb.append("流程执行失败。失败详情：\n");
            for (NodeExecution ne : execution.getNodes().values()) {
                if (ne.getStatus() == NodeExecution.Status.FAILED) {
                    sb.append("- 节点「").append(ne.getNodeName()).append("」：").append(ne.getError()).append('\n');
                }
            }
            return sb.toString().trim();
        }
        sb.append("流程「").append(execution.getFlowName()).append("」执行成功（runId=").append(execution.getRunId())
            .append("），各节点输出：\n");
        for (NodeExecution ne : execution.getNodes().values()) {
            if (ne.getStatus() != NodeExecution.Status.SUCCESS) {
                continue;
            }
            sb.append("\n【").append(ne.getNodeName()).append("】\n");
            String out = ne.getParsedJson() != null && !ne.getParsedJson().isBlank()
                ? ne.getParsedJson() : ne.getOutput();
            sb.append(out == null || out.isBlank() ? "（无文本输出）" : out).append('\n');
        }
        return sb.toString().trim();
    }
}
