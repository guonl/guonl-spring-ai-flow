package com.guonl.flow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 助手配置，前缀 {@code flow.assistant}。
 * <p>provider/model 留空时跟随 {@code flow.ai} 全局配置。</p>
 */
@Data
@ConfigurationProperties(prefix = "flow.assistant")
public class AssistantProperties {

    /** 总开关：false 时悬浮窗不注入、助手 API 返回禁用提示 */
    private boolean enabled = true;

    /** 模型服务模式：空=跟随 flow.ai.provider | mock | llm */
    private String provider = "";

    /** 助手模型覆盖（空=跟随 flow.ai.text-model） */
    private String model = "";

    /** 助手温度（对话场景建议低温） */
    private double temperature = 0.3;

    /** 会话记忆窗口轮数 */
    private int memoryTurns = 10;

    /** 是否启用助手工具集（P0=false 纯对话；P1 开启后挂载 AssistantTools） */
    private boolean toolsEnabled = false;

    /** 单条用户消息最大长度（防滥用） */
    private int maxMessageLength = 4000;

    /** 单次回答超时（秒） */
    private long timeoutSeconds = 120;
}
