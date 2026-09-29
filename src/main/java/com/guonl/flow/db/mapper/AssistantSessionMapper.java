package com.guonl.flow.db.mapper;

import com.guonl.flow.db.entity.AssistantSessionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * assistant_session 表 Mapper（AI 助手会话元数据 CRUD）。
 */
public interface AssistantSessionMapper {

    int insert(AssistantSessionDO record);

    AssistantSessionDO findByConversation(@Param("conversationId") String conversationId);

    /** 会话列表（updated_at 倒序，limit 控制） */
    List<AssistantSessionDO> listRecent(@Param("limit") int limit);

    int updateTitle(@Param("conversationId") String conversationId, @Param("title") String title);

    /** 消息数累加（用户+助手各一条时 delta=2） */
    int incrMessageCount(@Param("conversationId") String conversationId, @Param("delta") int delta);

    int deleteByConversation(@Param("conversationId") String conversationId);
}
