package com.guonl.flow.assistant.dto;

import lombok.Data;

/**
 * 助手消息反馈请求。
 */
@Data
public class FeedbackRequest {

    /** 会话ID */
    private String conversationId;

    /** 被反馈的消息原文（用于计算内容指纹） */
    private String content;

    /** 反馈：up / down；空为取消 */
    private String rating;
}
