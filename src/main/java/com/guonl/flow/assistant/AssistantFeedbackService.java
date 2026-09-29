package com.guonl.flow.assistant;

import com.guonl.flow.db.entity.AssistantFeedbackDO;
import com.guonl.flow.db.mapper.AssistantFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 助手消息反馈服务：👍/👎 评分、取消、按会话汇总。
 * <p>消息身份以「会话ID + 内容指纹（djb2 32位hex）」标识，前后端同一算法；
 * 重复反馈为改评（upsert），rating 置空为取消（删除）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantFeedbackService {

    private final AssistantFeedbackMapper mapper;

    /** 评分/取消：rating 为 up/down 时 upsert，否则视为取消 */
    public void rate(String conversationId, String content, String rating) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("conversationId 与 content 不能为空");
        }
        String hash = hash(content);
        boolean valid = "up".equals(rating) || "down".equals(rating);
        if (!valid) {
            mapper.deleteByMsg(conversationId, hash);
            return;
        }
        AssistantFeedbackDO record = new AssistantFeedbackDO();
        record.setConversationId(conversationId);
        record.setContentHash(hash);
        record.setRating(rating);
        record.setContentPreview(content.length() > 500 ? content.substring(0, 500) : content);
        mapper.upsert(record);
    }

    /** 会话内全部反馈（前端按 contentHash 高亮） */
    public List<AssistantFeedbackDO> listByConversation(String conversationId) {
        return mapper.listByConversation(conversationId);
    }

    /** 删除会话全部反馈（随会话删除清理） */
    public void deleteByConversation(String conversationId) {
        try {
            mapper.deleteByConversation(conversationId);
        } catch (Exception e) {
            log.warn("[Assistant] clear feedback failed, conv={}: {}", conversationId, e.getMessage());
        }
    }

    /** djb2 32位指纹 → 8位hex（与前端 assistant-chat.js acHash 同算法） */
    public static String hash(String content) {
        int h = 5381;
        for (int i = 0; i < content.length(); i++) {
            h = h * 33 + content.charAt(i);
        }
        return Integer.toHexString(h);
    }
}
