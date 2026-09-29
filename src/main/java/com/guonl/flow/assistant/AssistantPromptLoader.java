package com.guonl.flow.assistant;

import com.guonl.flow.config.AssistantProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 助手系统提示词加载器：读取 classpath:prompts/assistant-system.md（产品手册摘要），
 * 启动时加载缓存，调优提示词仅需改资源文件。
 */
@Slf4j
@Component
public class AssistantPromptLoader {

    private final AssistantProperties properties;

    private volatile String systemPrompt = "";

    public AssistantPromptLoader(AssistantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/assistant-system.md");
            try (var in = resource.getInputStream()) {
                this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.info("[Assistant] 系统提示词加载完成，{} 字符", systemPrompt.length());
        } catch (Exception e) {
            log.warn("[Assistant] 系统提示词加载失败，退化为内置简版: {}", e.getMessage());
            this.systemPrompt = "你是 guonl-spring-ai-flow 平台的内置 AI 助手「小流」，用中文简洁回答平台使用问题。";
        }
    }

    /** 系统提示词（手册摘要） */
    public String systemPrompt() {
        return StringUtils.hasText(systemPrompt) ? systemPrompt : "你是AI流程编排平台的助手。";
    }
}
