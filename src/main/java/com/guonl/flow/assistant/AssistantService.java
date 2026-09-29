package com.guonl.flow.assistant;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.assistant.dto.ChatEvent;
import com.guonl.flow.assistant.dto.ChatRequest;
import com.guonl.flow.assistant.dto.FeedbackView;
import com.guonl.flow.assistant.dto.MessageView;
import com.guonl.flow.assistant.dto.StatusView;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.config.AssistantProperties;
import com.guonl.flow.db.entity.AssistantSessionDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI 助手编排服务。
 * <p>provider=llm：经 {@link AiInvoker} 流式调用（onDelta 非空自动走真流式），
 * 会话记忆由 MessageChatMemoryAdvisor + JDBC 仓库自动读写（SPRING_AI_CHAT_MEMORY）；
 * provider=mock：意图路由（{@link MockAssistantResponder}，真实查询流程/运行数据），
 * 消息记忆由本服务手动写入同一张表，保证会话历史/跨页续聊行为一致。</p>
 * <p>P2 增强：重新生成（截断记忆最后一轮）、多模态贴图（dataURL → MediaItem）。</p>
 */
@Slf4j
@Service
public class AssistantService {

    /** mock 模式模拟流式的分片长度与间隔（打字机效果） */
    private static final int MOCK_CHUNK_SIZE = 12;

    private static final long MOCK_CHUNK_INTERVAL_MILLIS = 25;

    /** 多模态：单条消息最多附带图片数 */
    private static final int MAX_IMAGES = 3;

    /** 多模态：单张图片 base64 字符数上限（≈2MB 原始字节） */
    private static final int MAX_IMAGE_BASE64_CHARS = 2_800_000;

    private final AiProperties aiProperties;

    private final AssistantProperties properties;

    private final AssistantSessionService sessionService;

    private final AssistantFeedbackService feedbackService;

    private final AssistantPromptLoader promptLoader;

    private final AiInvoker aiInvoker;

    private final MockAssistantResponder mockResponder;

    /** JDBC 记忆仓库（starter 自动配置；异常场景缺失时 mock 记忆降级为不持久化） */
    private final ObjectProvider<JdbcChatMemoryRepository> memoryRepoProvider;

    public AssistantService(AiProperties aiProperties, AssistantProperties properties,
                            AssistantSessionService sessionService, AssistantFeedbackService feedbackService,
                            AssistantPromptLoader promptLoader,
                            AiInvoker aiInvoker, MockAssistantResponder mockResponder,
                            ObjectProvider<JdbcChatMemoryRepository> memoryRepoProvider) {
        this.aiProperties = aiProperties;
        this.properties = properties;
        this.sessionService = sessionService;
        this.feedbackService = feedbackService;
        this.promptLoader = promptLoader;
        this.aiInvoker = aiInvoker;
        this.mockResponder = mockResponder;
        this.memoryRepoProvider = memoryRepoProvider;
    }

    /** 对话编排：确认会话 → 分流 llm/mock → 过程事件回调 → 计数。异常以 error 事件回调，不再抛出 */
    public void chat(ChatRequest req, Consumer<ChatEvent> sink) {
        requireEnabled();
        String message = req.getMessage().trim();
        if (message.length() > properties.getMaxMessageLength()) {
            sink.accept(ChatEvent.of("error", Map.of("message",
                "消息过长（上限 " + properties.getMaxMessageLength() + " 字）")));
            return;
        }
        List<AiInvoker.MediaItem> medias = null;
        try {
            medias = parseImages(req.getImages());
        } catch (IllegalArgumentException e) {
            sink.accept(ChatEvent.of("error", Map.of("message", e.getMessage())));
            return;
        }
        String conv = StringUtils.hasText(req.getConversationId())
            ? req.getConversationId().trim() : newConversationId();
        if (req.isRegenerate()) {
            truncateLastTurn(conv);
        }
        sessionService.ensure(conv, message);
        sink.accept(ChatEvent.of("start", Map.of("conversationId", conv)));

        String answer;
        String model;
        try {
            if (isMock()) {
                model = "mock-assistant";
                answer = chatMock(conv, message, sink);
            } else {
                model = resolveModel();
                answer = chatLlm(conv, message, medias, sink);
            }
        } catch (Exception e) {
            log.error("[Assistant] chat failed, conv={}", conv, e);
            sink.accept(ChatEvent.of("error", Map.of("message", describe(e))));
            return;
        }
        // 重新生成时记忆被截断后重写同轮，会话计数不重复累加
        sessionService.incrMessageCount(conv, req.isRegenerate() ? 0 : 2);
        sink.accept(ChatEvent.of("done", Map.of(
            "content", answer == null ? "" : answer,
            "model", model,
            "conversationId", conv)));
    }

    /** llm 模式：流式调用（onDelta 非空时 SpringAiInvoker 自动走真流式），记忆由 advisor 自动持久化；
     *  tools-enabled=true 时挂载助手工具集（流程/运行/知识库查询 + NL建流程），轨迹以 tool_trace 事件回传 */
    private String chatLlm(String conv, String message, List<AiInvoker.MediaItem> medias, Consumer<ChatEvent> sink) {
        AiInvoker.InvokeRequest request = AiInvoker.InvokeRequest.builder()
            .bizKey("assistant")
            .systemPrompt(promptLoader.systemPrompt())
            .userPrompt(message)
            .model(StringUtils.hasText(properties.getModel()) ? properties.getModel() : null)
            .temperature(properties.getTemperature())
            .conversationId(conv)
            .memoryTurns(properties.getMemoryTurns())
            .useTools(properties.isToolsEnabled())
            .toolSet(AssistantTools.SET_NAME)
            .medias(medias)
            .onDelta(text -> sink.accept(ChatEvent.of("delta", Map.of("text", text))))
            .build();
        AiInvoker.InvokeResult result = aiInvoker.invoke(request);
        if (result.getToolCalls() != null && !result.getToolCalls().isEmpty()) {
            sink.accept(ChatEvent.of("tool_trace", result.getToolCalls()));
        }
        return result.getContent();
    }

    /** mock 模式：意图路由真实应答 + 手动写会话记忆 + 模拟流式输出（mock 不处理图片） */
    private String chatMock(String conv, String message, Consumer<ChatEvent> sink) {
        String answer = mockResponder.reply(message);
        writeMemory(conv, message, answer);
        for (int i = 0; i < answer.length(); i += MOCK_CHUNK_SIZE) {
            String chunk = answer.substring(i, Math.min(answer.length(), i + MOCK_CHUNK_SIZE));
            sink.accept(ChatEvent.of("delta", Map.of("text", chunk)));
            try {
                Thread.sleep(MOCK_CHUNK_INTERVAL_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return answer;
    }

    /** mock 模式手动写 JDBC 记忆（与 llm 模式的 advisor 写入同表同格式，messages API 无差别读取） */
    private void writeMemory(String conv, String userMessage, String assistantAnswer) {
        JdbcChatMemoryRepository repo = memoryRepoProvider.getIfAvailable();
        if (repo == null) {
            log.warn("[Assistant] JdbcChatMemoryRepository 不可用，mock 会话记忆不持久化");
            return;
        }
        var memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(Math.max(properties.getMemoryTurns(), 1) * 2)
            .build();
        memory.add(conv, new UserMessage(userMessage));
        memory.add(conv, new AssistantMessage(assistantAnswer));
    }

    /** 重新生成：截断会话记忆最后一轮（从尾部最后一条 user 消息起全部丢弃），之后按常规流程重新应答 */
    private void truncateLastTurn(String conv) {
        JdbcChatMemoryRepository repo = memoryRepoProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            List<Message> all = repo.findByConversationId(conv);
            int lastUser = -1;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).getMessageType() == MessageType.USER) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser < 0) {
                return;
            }
            List<Message> kept = new ArrayList<>(all.subList(0, lastUser));
            if (kept.isEmpty()) {
                repo.deleteByConversationId(conv);
            } else {
                repo.saveAll(conv, kept);
            }
            log.info("[Assistant] regenerate: truncated last turn, conv={}, kept={} messages", conv, kept.size());
        } catch (Exception e) {
            log.warn("[Assistant] regenerate truncate failed, conv={}: {}", conv, e.getMessage());
        }
    }

    /** 解析请求附带的 dataURL 图片为 MediaItem（数量/大小校验；空列表返回 null） */
    private List<AiInvoker.MediaItem> parseImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("最多附带 " + MAX_IMAGES + " 张图片");
        }
        List<AiInvoker.MediaItem> medias = new ArrayList<>();
        for (String dataUrl : images) {
            if (!StringUtils.hasText(dataUrl)) {
                continue;
            }
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:") || comma <= 0) {
                throw new IllegalArgumentException("图片格式不支持（需 dataURL，如 data:image/png;base64,…）");
            }
            String mime = dataUrl.substring(5, comma).split(";")[0];
            String b64 = dataUrl.substring(comma + 1);
            if (b64.length() > MAX_IMAGE_BASE64_CHARS) {
                throw new IllegalArgumentException("单张图片过大（压缩后仍超 2MB），请换小图或截图裁剪后重试");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("图片 base64 解码失败，请重试");
            }
            medias.add(AiInvoker.MediaItem.ofBytes("assistant-image-" + (medias.size() + 1), mime, bytes));
        }
        return medias.isEmpty() ? null : medias;
    }

    // ---------- 会话管理 ----------

    public List<AssistantSessionDO> sessions() {
        requireEnabled();
        return sessionService.list(50);
    }

    public List<MessageView> messages(String conversationId) {
        requireEnabled();
        JdbcChatMemoryRepository repo = memoryRepoProvider.getIfAvailable();
        if (repo == null) {
            return List.of();
        }
        return repo.findByConversationId(conversationId).stream()
            .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
            .map(m -> new MessageView(m.getMessageType() == MessageType.USER ? "user" : "assistant", m.getText()))
            .toList();
    }

    public void rename(String conversationId, String title) {
        requireEnabled();
        sessionService.rename(conversationId, title);
    }

    public void delete(String conversationId) {
        requireEnabled();
        JdbcChatMemoryRepository repo = memoryRepoProvider.getIfAvailable();
        if (repo != null) {
            try {
                repo.deleteByConversationId(conversationId);
            } catch (Exception e) {
                log.warn("[Assistant] clear chat memory failed, conv={}: {}", conversationId, e.getMessage());
            }
        }
        feedbackService.deleteByConversation(conversationId);
        sessionService.delete(conversationId);
    }

    /** 会话内消息反馈汇总（前端按 contentHash 高亮） */
    public List<FeedbackView> feedback(String conversationId) {
        requireEnabled();
        return feedbackService.listByConversation(conversationId).stream()
            .map(f -> new FeedbackView(f.getContentHash(), f.getRating()))
            .toList();
    }

    /** 消息评分/取消 */
    public void rate(String conversationId, String content, String rating) {
        requireEnabled();
        feedbackService.rate(conversationId, content, rating);
    }

    public StatusView status() {
        boolean mock = isMock();
        return new StatusView(properties.isEnabled(), resolveProvider(),
            mock ? "mock-assistant" : resolveModel(), properties.isToolsEnabled());
    }

    // ---------- 内部 ----------

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalArgumentException("AI 助手未启用（flow.assistant.enabled=false）");
        }
    }

    private boolean isMock() {
        return "mock".equalsIgnoreCase(resolveProvider());
    }

    /** 助手 provider：独立配置优先，空则跟随全局 flow.ai.provider */
    private String resolveProvider() {
        return StringUtils.hasText(properties.getProvider())
            ? properties.getProvider() : aiProperties.getProvider();
    }

    private String resolveModel() {
        return StringUtils.hasText(properties.getModel())
            ? properties.getModel() : aiProperties.getTextModel();
    }

    private String newConversationId() {
        return "a_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String describe(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
