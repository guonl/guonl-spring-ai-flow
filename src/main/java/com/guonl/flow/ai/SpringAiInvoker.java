package com.guonl.flow.ai;

import com.guonl.flow.ai.AiInvoker.InvokeRequest;
import com.guonl.flow.ai.AiInvoker.InvokeResult;
import com.guonl.flow.ai.AiInvoker.MediaItem;
import com.guonl.flow.ai.AiInvoker.ToolCallTrace;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.tool.FlowTools;
import com.guonl.flow.mcp.TracedMcpToolCallback;
import com.openai.core.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型实现：基于 Spring AI {@link ChatClient} 经 OpenAI 兼容协议直连大模型。
 * <p>整合原「业务侧网关 + AI侧服务」两层调用为进程内一次调用：
 * prompt 走用户消息文本，图片素材走多模态 Media（自动下载字节转base64，
 * 规避模型网关无法访问外部URL的问题），模型按「节点覆盖 > 视觉模型(含图) > 文本模型」路由，
 * 温度、token上限经 {@link OpenAiChatOptions} 传递。</p>
 */
@Slf4j
public class SpringAiInvoker implements AiInvoker {

    /** 会话ID的advisor上下文参数键（Spring AI 2.0.1 无公开常量，取自 BaseChatMemoryAdvisor.getConversationId 字节码确认的字面量） */
    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatClient chatClient;

    private final AiProperties aiProperties;

    /** 图片下载客户端：服务端代下载后转base64 */
    private final RestClient imageClient;

    /** 内置工具集：工具调用节点挂载给模型自主调用 */
    private final FlowTools flowTools;

    /** JDBC会话记忆仓库（starter自动配置，消息持久化到MySQL SPRING_AI_CHAT_MEMORY表） */
    private final JdbcChatMemoryRepository chatMemoryRepository;

    /**
     * MCP客户端工具来源（spring.ai.mcp.client 启用且有连接时由自动配置提供；
     * 未启用时 ObjectProvider 为空，仅使用内置工具）
     */
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolsProvider;

    /** 场景化工具集提供者（助手等，按 InvokeRequest.toolSet 标识匹配；空则全部回退内置工具） */
    private final ObjectProvider<ToolSetProvider> toolSetProviders;

    public SpringAiInvoker(ChatClient.Builder chatClientBuilder, AiProperties aiProperties, FlowTools flowTools,
                           JdbcChatMemoryRepository chatMemoryRepository,
                           ObjectProvider<SyncMcpToolCallbackProvider> mcpToolsProvider,
                           ObjectProvider<ToolSetProvider> toolSetProviders) {
        this.chatClient = chatClientBuilder.build();
        this.aiProperties = aiProperties;
        this.flowTools = flowTools;
        this.chatMemoryRepository = chatMemoryRepository;
        this.mcpToolsProvider = mcpToolsProvider;
        this.toolSetProviders = toolSetProviders;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(aiProperties.getImageDownloadTimeoutSeconds()));
        this.imageClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public InvokeResult invoke(InvokeRequest request) {
        // 流式路径：不走重试循环，避免重试导致 SSE 消费端收到重复增量
        if (request.getOnDelta() != null) {
            return streamInvoke(request);
        }
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

    /** 瞬时错误启发式判断（限流/超时/连接抖动等值得重试的错误） */
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

    /** 单次实际调用：modelOverride 非空时覆盖模型路由（用于 fallback 降级） */
    private InvokeResult doInvoke(InvokeRequest request, String modelOverride) {
        List<Media> medias = toMedias(request.getMedias());
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride : resolveModel(request.getModel(), !medias.isEmpty());
        OpenAiChatOptions.Builder options = buildOptions(model, request.getTemperature());
        long start = System.currentTimeMillis();
        log.info("[AI] invoke start, biz={}, model={}, request={}",
            request.getBizKey(), model, request);
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(options)
                .system(s -> {
                    if (StringUtils.hasText(request.getSystemPrompt())) {
                        s.text(request.getSystemPrompt());
                    }
                })
                .user(u -> {
                    u.text(request.getUserPrompt());
                    if (!medias.isEmpty()) {
                        u.media(medias.toArray(Media[]::new));
                    }
                });
            // 工具调用节点：按 toolSet 挂载工具集（默认内置+MCP；助手等场景走 ToolSetProvider），轨迹经 ToolContext 传递（线程无关）
            List<ToolCallTrace> trace = null;
            if (request.isUseTools()) {
                trace = new ArrayList<>();
                spec = spec.tools(resolveTools(request.getToolSet()))
                    .toolContext(Map.of(FlowTools.TRACE_KEY, trace));
            }
            // 会话记忆：挂载JDBC记忆advisor（多轮历史持久化到MySQL）
            spec = withMemory(spec, request);
            ChatResponse chatResponse = spec.call().chatResponse();
            String content = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText() : null;
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("模型未返回有效输出");
            }
            InvokeResult result = InvokeResult.builder()
                .content(content)
                .model(model)
                .toolCalls(trace)
                .build();
            fillUsage(result, chatResponse);
            log.info("[AI] invoke ok, biz={}, model={}, cost={}ms", request.getBizKey(), model, System.currentTimeMillis() - start);
            log.info("[AI] invoke ok, biz={}, model={}, result={}", request.getBizKey(), model, result);
            return result;
        } catch (Exception e) {
            log.error("[AI] invoke failed, biz={}, model={}, cost={}ms, reason={}",
                request.getBizKey(), model, System.currentTimeMillis() - start, describeError(e), e);
            throw new IllegalStateException("AI调用失败: " + describeError(e), e);
        }
    }

    /** 流式调用：Spring AI Flux 流，逐chunk回调；首块前出错则抛出 */
    @Override
    public void stream(InvokeRequest request, Consumer<String> onChunk) {
        List<Media> medias = toMedias(request.getMedias());
        doStream(request, medias, resolveModel(request.getModel(), !medias.isEmpty()), onChunk, null);
    }

    /** 流式路径的 invoke：聚合全文作为结果（不重试，防止增量重复推送） */
    private InvokeResult streamInvoke(InvokeRequest request) {
        List<Media> medias = toMedias(request.getMedias());
        String model = resolveModel(request.getModel(), !medias.isEmpty());
        List<ToolCallTrace> trace = request.isUseTools() ? new ArrayList<>() : null;
        String content = doStream(request, medias, model, request.getOnDelta(), trace);
        return InvokeResult.builder().content(content).model(model).toolCalls(trace).build();
    }

    /** 流式核心：订阅 Flux 逐chunk回调，返回聚合全文；首块前出错则抛出 */
    private String doStream(InvokeRequest request, List<Media> medias, String model,
                            Consumer<String> onChunk, List<ToolCallTrace> trace) {
        OpenAiChatOptions.Builder options = buildOptions(model, request.getTemperature());
        long start = System.currentTimeMillis();
        StringBuilder received = new StringBuilder();
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(options)
                .system(s -> {
                    if (StringUtils.hasText(request.getSystemPrompt())) {
                        s.text(request.getSystemPrompt());
                    }
                })
                .user(u -> {
                    u.text(request.getUserPrompt());
                    if (!medias.isEmpty()) {
                        u.media(medias.toArray(Media[]::new));
                    }
                });
            if (trace != null) {
                spec = spec.tools(resolveTools(request.getToolSet()))
                    .toolContext(Map.of(FlowTools.TRACE_KEY, trace));
            }
            // 会话记忆：挂载JDBC记忆advisor（多轮历史持久化到MySQL）
            spec = withMemory(spec, request);
            spec.stream()
                .content()
                .doOnNext(chunk -> {
                    received.append(chunk);
                    onChunk.accept(chunk);
                })
                .blockLast();
            log.info("[AI] stream ok, biz={}, model={}, cost={}ms", request.getBizKey(), model, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[AI] stream failed, biz={}, model={}, reason={}", request.getBizKey(), model, describeError(e), e);
            if (received.length() == 0) {
                throw new IllegalStateException("AI流式调用失败: " + describeError(e), e);
            }
        }
        return received.toString();
    }

    /**
     * 按工具集标识解析工具对象：null/"flow"=流程引擎内置工具（+MCP 见 mcpCallbacks，仅默认集挂载）；
     * 其余标识在 ToolSetProvider 中匹配（如助手工具集，不再叠加 MCP，避免无关工具干扰对话）。
     * 未匹配到提供者时回退内置工具并告警（调用方可感知缺工具）。
     */
    private Object resolveTools(String toolSet) {
        if (toolSet == null || toolSet.isBlank() || "flow".equals(toolSet)) {
            return flowTools;
        }
        for (ToolSetProvider provider : toolSetProviders) {
            if (provider.toolSet().equals(toolSet)) {
                return provider.tools();
            }
        }
        log.warn("[Invoker] toolSet={} 未找到 ToolSetProvider，回退内置工具", toolSet);
        return flowTools;
    }

    /**
     * 枚举MCP客户端发现的工具并包上轨迹装饰器。
     * <p>未启用MCP客户端 / 无可用连接 / 枚举失败时返回空列表（仅降级为内置工具，不阻断调用）。
     * 每次枚举会向各MCP server发起 listTools，保证工具清单动态更新。</p>
     */
    private List<ToolCallback> mcpCallbacks() {
        try {
            if (mcpToolsProvider == null) {
                return List.of();
            }
            SyncMcpToolCallbackProvider provider = mcpToolsProvider.getIfAvailable();
            if (provider == null) {
                return List.of();
            }
            return Arrays.stream(provider.getToolCallbacks())
                .map(TracedMcpToolCallback::new)
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.warn("[AI] MCP工具枚举失败，本次仅使用内置工具: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 提取可读的错误原因：openai-java SDK 的异常体（statusCode + 原始响应体），
     * 避免只拿到 "400: Unknown" 这类模糊信息
     */
    private String describeError(Throwable e) {
        if (e instanceof com.openai.errors.OpenAIServiceException se) {
            Object known = null;
            try {
                known = se.body().asKnown().orElseGet(() -> se.body().asString().orElse(null));
            } catch (Exception ignored) {
                // body 不可读时降级为状态码
            }
            String body = known == null ? "" : (known instanceof String s ? s : String.valueOf(known));
            if (!StringUtils.hasText(body)) {
                return "HTTP " + se.statusCode() + " (响应体为空，常见于网关或安全代理拦截，请检查终端网络对该进程的放行)";
            }
            return "HTTP " + se.statusCode() + " " + body;
        }
        return e.getMessage();
    }

    /** 统一构建模型选项（Spring AI 2.x 的 ChatClient.options 接受 Builder） */
    private OpenAiChatOptions.Builder buildOptions(String model, Double temperatureOverride) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model)
            .temperature(temperatureOverride != null ? temperatureOverride : aiProperties.getTemperature());
        if (aiProperties.getMaxOutputTokens() != null) {
            builder.maxTokens(aiProperties.getMaxOutputTokens());
        }
        return builder;
    }

    /**
     * 会话记忆：memoryTurns>0 且带会话ID时挂载JDBC记忆advisor。
     * <p>窗口=N轮×2条消息（USER+ASSISTANT），历史经 {@link JdbcChatMemoryRepository} 持久化到MySQL，
     * 同一会话ID跨多次运行共享；会话ID经advisor上下文参数传递（键 {@link #CONVERSATION_ID_KEY}）。</p>
     */
    private ChatClient.ChatClientRequestSpec withMemory(ChatClient.ChatClientRequestSpec spec, InvokeRequest request) {
        Integer turns = request.getMemoryTurns();
        if (turns == null || turns <= 0 || !StringUtils.hasText(request.getConversationId())) {
            return spec;
        }
        ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(turns * 2)
            .build();
        Advisor advisor = MessageChatMemoryAdvisor.builder(memory).build();
        return spec.advisors(advisor)
            .advisors(a -> a.param(CONVERSATION_ID_KEY, request.getConversationId()));
    }

    private void fillUsage(InvokeResult result, ChatResponse chatResponse) {
        try {
            Usage usage = chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
            if (usage != null) {
                result.setPromptTokens(usage.getPromptTokens() > 0 ? (int) usage.getPromptTokens() : null);
                result.setCompletionTokens(usage.getCompletionTokens() > 0 ? (int) usage.getCompletionTokens() : null);
                result.setTotalTokens(usage.getTotalTokens() > 0 ? (int) usage.getTotalTokens() : null);
            }
        } catch (Exception e) {
            log.debug("[AI] parse usage ignored", e);
        }
    }

    /** 模型路由：节点覆盖 > 含图走视觉模型 > 默认文本模型 */
    private String resolveModel(String modelOverride, boolean hasMedia) {
        if (StringUtils.hasText(modelOverride)) {
            return modelOverride;
        }
        return hasMedia ? aiProperties.getVisionModel() : aiProperties.getTextModel();
    }

    /**
     * 素材转 Spring AI 多模态 Media：字节数据直接转base64 data URL；
     * URL素材优先由服务端下载，下载失败回退URL直传，单个失败素材仅告警跳过
     */
    private List<Media> toMedias(List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Media> medias = new ArrayList<>();
        for (MediaItem item : items) {
            MimeType mimeType = StringUtils.hasText(item.mimeType())
                ? MimeTypeUtils.parseMimeType(item.mimeType()) : MimeTypeUtils.IMAGE_PNG;
            try {
                if (item.data() != null && item.data().length > 0) {
                    medias.add(new Media(mimeType, new ByteArrayResource(item.data())));
                } else if (StringUtils.hasText(item.url())) {
                    byte[] bytes = imageClient.get().uri(URI.create(item.url())).retrieve().body(byte[].class);
                    if (bytes == null || bytes.length == 0) {
                        throw new IllegalStateException("empty image body");
                    }
                    medias.add(new Media(mimeType, new ByteArrayResource(bytes)));
                }
            } catch (Exception e) {
                if (StringUtils.hasText(item.url())) {
                    log.warn("[AI] image download failed, fallback to raw url: {}", item.url(), e);
                    try {
                        medias.add(new Media(mimeType, URI.create(item.url())));
                    } catch (Exception ex) {
                        log.warn("[AI] invalid media ignored: {}", item.name(), ex);
                    }
                } else {
                    log.warn("[AI] invalid media ignored: {}", item.name(), e);
                }
            }
        }
        return medias;
    }
}
