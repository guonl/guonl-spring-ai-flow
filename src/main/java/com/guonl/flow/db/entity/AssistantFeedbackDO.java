package com.guonl.flow.db.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * assistant_feedback 表实体（AI 助手消息反馈）。
 * <p>按 会话ID + 消息内容指纹 唯一：同一条消息重复反馈为改评， rating 置空为取消。</p>
 */
@Data
public class AssistantFeedbackDO {

    /** 主键 */
    private Long id;

    /** 会话ID（与 SPRING_AI_CHAT_MEMORY.conversation_id 对齐） */
    private String conversationId;

    /** 消息内容指纹（djb2 32位hex，前后端同算法） */
    private String contentHash;

    /** 反馈：up / down */
    private String rating;

    /** 消息内容预览（前500字） */
    private String contentPreview;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
