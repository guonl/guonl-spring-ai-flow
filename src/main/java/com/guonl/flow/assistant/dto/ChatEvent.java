package com.guonl.flow.assistant.dto;

/**
 * SSE 事件体：{@code data: {"event":"delta","data":{...}}\n\n}。
 *
 * @param event 事件类型：start/delta/status/tool_trace/done/error
 * @param data  事件载荷
 */
public record ChatEvent(String event, Object data) {

    public static ChatEvent of(String event, Object data) {
        return new ChatEvent(event, data);
    }
}
