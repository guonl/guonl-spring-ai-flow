package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.AssistantFeedbackDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * assistant_feedback 表 Mapper（AI 助手消息反馈 CRUD）。
 */
public interface AssistantFeedbackMapper {

    /** 按会话+指纹 upsert（存在则改评） */
    int upsert(AssistantFeedbackDO record);

    /** 取消某条消息的反馈 */
    int deleteByMsg(@Param("conversationId") String conversationId, @Param("contentHash") String contentHash);

    /** 会话内全部反馈（用于前端按指纹高亮） */
    List<AssistantFeedbackDO> listByConversation(@Param("conversationId") String conversationId);

    /** 删除会话全部反馈（随会话删除清理） */
    int deleteByConversation(@Param("conversationId") String conversationId);
}
