package com.guonl.flow.assistant;

import com.guonl.flow.db.entity.AssistantSessionDO;
import com.guonl.flow.db.mapper.AssistantSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 助手会话元数据服务：会话的创建/列表/标题/计数/删除。
 * <p>消息正文存于 Spring AI JDBC ChatMemory（SPRING_AI_CHAT_MEMORY），本服务只管会话级元数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSessionService {

    /** 标题截断长度（首条用户消息前N字） */
    private static final int TITLE_MAX_LEN = 40;

    private final AssistantSessionMapper mapper;

    /** 会话不存在则创建（标题取首条用户消息前40字；并发重复创建时容忍幂等返回） */
    public AssistantSessionDO ensure(String conversationId, String titleSeed) {
        AssistantSessionDO existing = mapper.findByConversation(conversationId);
        if (existing != null) {
            return existing;
        }
        AssistantSessionDO record = new AssistantSessionDO();
        record.setConversationId(conversationId);
        record.setTitle(buildTitle(titleSeed));
        record.setMessageCount(0);
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下另一请求已创建，容忍即可
        }
        return mapper.findByConversation(conversationId);
    }

    /** 会话列表（最近更新优先） */
    public List<AssistantSessionDO> list(int limit) {
        return mapper.listRecent(limit);
    }

    public AssistantSessionDO findByConversation(String conversationId) {
        return mapper.findByConversation(conversationId);
    }

    public void incrMessageCount(String conversationId, int delta) {
        mapper.incrMessageCount(conversationId, delta);
    }

    public void rename(String conversationId, String title) {
        if (StringUtils.hasText(title)) {
            mapper.updateTitle(conversationId, truncate(title));
        }
    }

    /** 删除会话元数据（消息正文清理由 AssistantService 负责 ChatMemory） */
    public void delete(String conversationId) {
        mapper.deleteByConversation(conversationId);
    }

    private String buildTitle(String seed) {
        if (!StringUtils.hasText(seed)) {
            return "新会话";
        }
        // 压缩空白后截断，避免标题全为换行
        String compact = seed.replaceAll("\\s+", " ").trim();
        return truncate(compact);
    }

    private String truncate(String text) {
        return text.length() <= TITLE_MAX_LEN ? text : text.substring(0, TITLE_MAX_LEN);
    }
}
