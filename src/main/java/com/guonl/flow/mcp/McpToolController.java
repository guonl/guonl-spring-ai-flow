package com.guonl.flow.mcp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP客户端工具发现 API：列出外部 MCP server 上发现的工具（工具调用节点的可选工具来源）。
 * <p>spring.ai.mcp.client 未启用或无可用连接时返回空清单。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP工具发现", description = "列出MCP客户端从外部MCP server发现的工具")
public class McpToolController {

    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbacks;

    @Operation(summary = "MCP工具清单", description = "实时向各外部MCP server发起 listTools，返回 {clientEnabled, tools:[{name,description}]}")
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> result = new LinkedHashMap<>();
        SyncMcpToolCallbackProvider provider = mcpToolCallbacks.getIfAvailable();
        boolean enabled = provider != null;
        result.put("clientEnabled", enabled);
        List<Map<String, String>> tools = new ArrayList<>();
        if (enabled) {
            try {
                ToolCallback[] callbacks = provider.getToolCallbacks();
                for (ToolCallback cb : callbacks) {
                    ToolDefinition def = cb.getToolDefinition();
                    Map<String, String> tool = new LinkedHashMap<>();
                    tool.put("name", def.name());
                    tool.put("description", def.description());
                    tools.add(tool);
                }
            } catch (Exception e) {
                log.warn("[MCP] 工具枚举失败: {}", e.getMessage());
                result.put("error", "工具枚举失败: " + e.getMessage());
            }
        }
        result.put("tools", tools);
        return result;
    }
}
