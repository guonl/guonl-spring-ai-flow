package com.guonl.flow.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 助手对话请求。
 */
@Data
public class ChatRequest {

    /** 会话ID（空=新建会话） */
    private String conversationId;

    /** 用户消息 */
    @NotBlank(message = "消息不能为空")
    @Size(max = 4000, message = "单条消息不能超过4000字")
    private String message;

    /** 重新生成：true 时截断会话记忆中最后一轮（user+assistant），再按本次消息重新应答 */
    private Boolean regenerate;

    /** 附带图片（dataURL 形式，data:image/...;base64,...），仅 llm 模式生效，最多3张 */
    private List<String> images;

    public boolean isRegenerate() {
        return Boolean.TRUE.equals(regenerate);
    }
}
