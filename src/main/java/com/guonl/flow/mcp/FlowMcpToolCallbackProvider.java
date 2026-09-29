package com.guonl.flow.mcp;

import com.guonl.flow.core.engine.FlowRunService;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.store.FlowStore;
import com.guonl.flow.core.store.RunStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把流程库中全部已保存流程暴露为 MCP tools。
 * <p>Spring AI 的 MCP Server 自动配置会收集 ToolCallbackProvider/ToolCallback bean，
 * 经 ToolCallbackConverter 转为 SyncToolSpecification 注册到 McpSyncServer。</p>
 * <p>工具清单在启动时构建：流程增删改后需重启（或调用方重新 list）生效。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flow.mcp.expose.enabled", matchIfMissing = true)
public class FlowMcpToolCallbackProvider implements ToolCallbackProvider {

    private final FlowStore flowStore;

    private final FlowRunService flowRunService;

    private final RunStore runStore;

    private final long waitTimeoutMs;

    /**
     * flowRunService 用 @Lazy 注入：它依赖 FlowEngine，而 FlowEngine 链路的下游
     * （ChatModel 工具解析器）又会收集本 Provider，形成 bean 循环依赖
     * （仅 provider=llm 时触发，mock 模式下不创建 SpringAiInvoker 故环断开）。
     * 这里延迟到首次执行流程工具时才解析真实 bean，启动期零依赖。
     */
    public FlowMcpToolCallbackProvider(FlowStore flowStore,
                                       @Lazy FlowRunService flowRunService,
                                       RunStore runStore,
                                       @Value("${flow.mcp.expose.wait-timeout-seconds:180}") long waitTimeoutSeconds) {
        this.flowStore = flowStore;
        this.flowRunService = flowRunService;
        this.runStore = runStore;
        this.waitTimeoutMs = waitTimeoutSeconds * 1000;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<FlowDefinition> flows = flowStore.list();
        log.info("[MCP] 暴露流程为tools：{} 个", flows.size());
        return flows.stream()
            .map(f -> (ToolCallback) new FlowMcpToolCallback(f, flowRunService, runStore, waitTimeoutMs))
            .toArray(ToolCallback[]::new);
    }
}
