package com.guonl.flow.assistant.dto;

/** 消息反馈视图：指纹 + 评分（前端按指纹匹配高亮） */
public record FeedbackView(String contentHash, String rating) {
}
