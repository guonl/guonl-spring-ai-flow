package com.guonl.flow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.assistant.AssistantService;
import com.guonl.flow.assistant.dto.ChatEvent;
import com.guonl.flow.assistant.dto.ChatRequest;
import com.guonl.flow.assistant.dto.FeedbackRequest;
import com.guonl.flow.assistant.dto.FeedbackView;
import com.guonl.flow.assistant.dto.MessageView;
import com.guonl.flow.assistant.dto.StatusView;
import com.guonl.flow.config.AssistantProperties;
import com.guonl.flow.db.entity.AssistantSessionDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 助手 REST API：SSE 流式对话 + 会话管理。
 * <p>SSE 协议：统一 {@code data: {"event":..,"data":{..}}\n\n}，
 * 事件类型 start/delta/status/tool_trace/done/error，15s 心跳注释行防代理超时。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
@Tag(name = "AI 助手", description = "流式对话（SSE）、会话列表/历史/删除、助手状态")
public class AssistantController {

    /** SSE 流式执行线程池（daemon，不阻塞容器线程） */
    private final ExecutorService ssePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "assistant-sse");
        t.setDaemon(true);
        return t;
    });

    /** 共享心跳调度器（每条流一个15s定时任务，流结束即 cancel） */
    private final java.util.concurrent.ScheduledExecutorService heartbeatPool =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "assistant-heartbeat");
            t.setDaemon(true);
            return t;
        });

    private final AssistantService assistantService;

    private final AssistantProperties properties;

    /** SSE 事件 JSON 序列化器（ObjectMapper线程安全；独立实例避免依赖自动装配，与全项目惯例一致） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "发送消息（SSE流式）", description = "conversationId 为空时新建会话；返回 text/event-stream（start/delta/tool_trace/done/error 事件）")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        assistantService.status(); // 触发 enabled 校验（未启用抛400）
        long timeoutMillis = properties.getTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable cleanup = () -> {
            ScheduledFuture<?> hb = heartbeat.get();
            if (hb != null) {
                hb.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        // 15s 心跳注释行，防反向代理空闲断连
        heartbeat.set(heartbeatPool.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ignored) {
                // 客户端已断开，等待 cleanup
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS));

        ssePool.submit(() -> {
            try {
                assistantService.chat(request, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                // 客户端断开（send 失败）属正常现象；其余错误已作为 error 事件下发
                log.info("[Assistant] chat stream ended: {}", e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed: " + e.getMessage(), e);
        }
    }

    @Operation(summary = "会话列表", description = "按最近更新倒序，最多50条")
    @GetMapping("/sessions")
    public List<AssistantSessionDO> sessions() {
        return assistantService.sessions();
    }

    @Operation(summary = "会话消息历史", description = "从会话记忆（SPRING_AI_CHAT_MEMORY）读取，仅含 user/assistant 消息")
    @GetMapping("/sessions/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable String conversationId) {
        return assistantService.messages(conversationId);
    }

    @Operation(summary = "消息反馈", description = "rating=up/down 评分（重复提交为改评）；空为取消反馈")
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackRequest request) {
        assistantService.rate(request.getConversationId(), request.getContent(), request.getRating());
        return Map.of("ok", true);
    }

    @Operation(summary = "会话反馈汇总", description = "返回会话内各消息的评分：[{contentHash, rating}]，前端按指纹匹配高亮")
    @GetMapping("/sessions/{conversationId}/feedback")
    public List<FeedbackView> feedbackList(@PathVariable String conversationId) {
        return assistantService.feedback(conversationId);
    }

    @Operation(summary = "会话重命名")
    @PostMapping("/sessions/{conversationId}/rename")
    public Map<String, Object> rename(@PathVariable String conversationId, @RequestParam String title) {
        assistantService.rename(conversationId, title);
        return Map.of("renamed", true);
    }

    @Operation(summary = "删除会话", description = "同时清理会话元数据与消息记忆")
    @DeleteMapping("/sessions/{conversationId}")
    public Map<String, Object> delete(@PathVariable String conversationId) {
        assistantService.delete(conversationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return result;
    }

    @Operation(summary = "助手状态", description = "悬浮窗角标/独立页横幅数据源：enabled/provider/model/toolsEnabled")
    @GetMapping("/status")
    public StatusView status() {
        return assistantService.status();
    }
}
