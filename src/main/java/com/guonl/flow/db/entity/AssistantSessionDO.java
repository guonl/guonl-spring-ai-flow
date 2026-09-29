package com.guonl.flow.db.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * assistant_session 表实体（AI 助手会话元数据）。
 * <p>消息正文存于 Spring AI JDBC ChatMemory（SPRING_AI_CHAT_MEMORY），本表仅存会话级元数据。</p>
 */
@Data
public class AssistantSessionDO {

    /** 主键 */
    private Long id;

    /** 会话ID（与 SPRING_AI_CHAT_MEMORY.conversation_id 对齐） */
    private String conversationId;

    /** 标题（首条用户消息前40字） */
    private String title;

    /** 消息条数（用户+助手） */
    private Integer messageCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
