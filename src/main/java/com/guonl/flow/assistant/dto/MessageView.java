package com.guonl.flow.assistant.dto;

/**
 * 会话消息视图（角色 + 内容）。
 */
public record MessageView(String role, String content) {
}
