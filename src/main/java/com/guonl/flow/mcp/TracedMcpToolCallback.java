package com.guonl.flow.mcp;

import com.guonl.flow.core.tool.FlowTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * MCP工具轨迹装饰器：委托外部MCP工具调用，并把调用轨迹写入 ToolContext 携带的列表，
 * 与内置工具（{@link FlowTools}）共用同一套 ToolCallTrace 轨迹格式，
 * 使工具调用节点面板能统一展示「内置 + MCP」全部工具轨迹。
 * <p>调用异常不向外抛出，而是转换为错误文本返回给模型（与内置工具行为一致，
 * 让模型有机会看到错误并自行调整参数重试）。</p>
 */
@RequiredArgsConstructor
public class TracedMcpToolCallback implements ToolCallback {

    /** 轨迹中记录的参数最大长度（MCP工具入参JSON可能很长，超长截断） */
    private static final int MAX_ARGS_LENGTH = 500;

    private final ToolCallback delegate;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        // 无 ToolContext 时无从记录轨迹，直接透传
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        String args = toolInput == null ? "" : toolInput;
        try {
            String result = delegate.call(toolInput, toolContext);
            FlowTools.record(name, truncate(args), result, toolContext);
            return result;
        } catch (Exception e) {
            String error = "工具调用失败: " + e.getMessage();
            FlowTools.record(name, truncate(args), error, toolContext);
            return error;
        }
    }

    private static String truncate(String args) {
        return args.length() > MAX_ARGS_LENGTH ? args.substring(0, MAX_ARGS_LENGTH) + "…(已截断)" : args;
    }
}
