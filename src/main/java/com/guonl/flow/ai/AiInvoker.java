package com.guonl.flow.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI模型统一调用门面SPI。
 * <p>屏蔽大模型协议细节：调用方只面向「系统提示词 + 用户提示词 + 多模态素材 + 模型选项」编程，
 * 返回统一的结果结构（原始输出 + 用量统计）。业务编排层不直接依赖 Spring AI 类型。</p>
 */
public interface AiInvoker {

    /**
     * 执行一次模型调用
     *
     * @param request 调用请求
     * @return 统一结果
     * @throws RuntimeException 模型调用失败时抛出，由引擎统一记录
     */
    InvokeResult invoke(InvokeRequest request);

    /**
     * 流式调用：逐块回调输出（打字机效果）。
     * <p>默认实现退化为一次性调用；实现类可覆写为真流式。</p>
     *
     * @param request 调用请求
     * @param onChunk 每收到一个文本片段回调一次
     */
    default void stream(InvokeRequest request, Consumer<String> onChunk) {
        InvokeResult result = invoke(request);
        if (result.getContent() != null && !result.getContent().isEmpty()) {
            onChunk.accept(result.getContent());
        }
    }

    /** 调用请求 */
    @Data
    @Builder
    class InvokeRequest {

        /** 业务标识（场景/节点编码，用于日志与mock路由） */
        private String bizKey;

        /** 系统提示词（角色设定，可为空） */
        private String systemPrompt;

        /** 用户提示词（指令 + 数据 + 输出契约） */
        private String userPrompt;

        /** 多模态素材（图片），可为空 */
        private List<MediaItem> medias;

        /** 模型名覆盖（空则按文本/视觉自动路由） */
        private String model;

        /** 温度覆盖 */
        private Double temperature;

        /** 流式增量回调（非空时走流式路径，逐片段推送打字机效果） */
        private Consumer<String> onDelta;

        /** 是否挂载内置工具集（工具调用节点为 true，模型可自主决定调用工具） */
        private boolean useTools;

        /**
         * 工具集标识（useTools=true 时生效）：null/"flow"=流程引擎内置工具+MCP发现工具（默认）；
         * "assistant"=AI 助手专属工具集（经 {@link ToolSetProvider} 注册，见 assistant 包）。
         */
        private String toolSet;

        /** 会话ID（多轮记忆隔离键；空=无记忆） */
        private String conversationId;

        /** 记忆窗口轮数（携带最近N轮历史消息；null/0=不启用记忆） */
        private Integer memoryTurns;
    }

    /** 一次工具调用的执行轨迹（名称+入参+结果） */
    record ToolCallTrace(String name, String arguments, String result) {
    }

    /** 多模态素材项 */
    record MediaItem(String name, String mimeType, byte[] data, String url) {
        public static MediaItem ofBytes(String name, String mimeType, byte[] data) {
            return new MediaItem(name, mimeType, data, null);
        }

        public static MediaItem ofUrl(String name, String mimeType, String url) {
            return new MediaItem(name, mimeType, null, url);
        }
    }

    /** 调用结果 */
    @Data
    @Builder
    class InvokeResult {

        /** 模型原始输出 */
        private String content;

        /** 实际使用的模型 */
        private String model;

        /** 输入token数（可能为空） */
        private Integer promptTokens;

        /** 输出token数（可能为空） */
        private Integer completionTokens;

        /** 总token数（可能为空） */
        private Integer totalTokens;

        /** 工具调用执行轨迹（仅工具调用节点可能非空，按执行顺序） */
        private List<ToolCallTrace> toolCalls;
    }
}
