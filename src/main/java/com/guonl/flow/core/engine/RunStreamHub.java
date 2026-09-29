package com.guonl.flow.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行流式输出中枢：节点增量输出 -> SSE 订阅者（运行页打字机效果）。
 * <p>同一运行允许多个订阅者（多标签页同时观看）；订阅者断开自动摘除；
 * 运行结束时发送 done 帧并关闭全部订阅。</p>
 */
@Component
public class RunStreamHub {

    private static final Logger log = LoggerFactory.getLogger(RunStreamHub.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** runId -> 活跃订阅者 */
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 订阅一次运行的增量输出流 */
    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，结束由 complete 主动关闭
        List<SseEmitter> list = subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** 推送一个节点的增量片段到该运行的所有订阅者 */
    public void publish(String runId, String nodeId, String delta) {
        List<SseEmitter> list = subscribers.get(runId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String payload = encode(nodeId, delta);
        if (payload == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().data(payload));
            } catch (Exception e) {
                list.remove(emitter); // 订阅者已断开，摘除
            }
        }
    }

    /** 运行结束：通知全部订阅者关闭并清理 */
    public void complete(String runId) {
        List<SseEmitter> list = subscribers.remove(runId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().data("{\"done\":true}"));
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE 订阅者关闭失败（已忽略）：{}", e.getMessage());
            }
        }
    }

    private String encode(String nodeId, String delta) {
        try {
            return objectMapper.writeValueAsString(Map.of("nodeId", nodeId, "delta", delta));
        } catch (Exception e) {
            log.warn("SSE 增量序列化失败（已丢弃）：{}", e.getMessage());
            return null;
        }
    }
}
