package com.guonl.flow.config;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.ai.MockAiInvoker;
import com.guonl.flow.ai.SpringAiInvoker;
import com.guonl.flow.core.tool.FlowTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI能力装配：按 flow.ai.provider 切换实现。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({AiProperties.class, FlowProperties.class, AssistantProperties.class})
public class AiConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "flow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
    public AiInvoker mockAiInvoker(AiProperties aiProperties, FlowTools flowTools) {
        log.info("[AI] use MockAiInvoker（mock模式，返回模拟数据）");
        return new MockAiInvoker(aiProperties, flowTools);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flow.ai", name = "provider", havingValue = "llm")
    public AiInvoker springAiInvoker(ChatClient.Builder chatClientBuilder, AiProperties aiProperties, FlowTools flowTools,
                                     org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository chatMemoryRepository,
                                     org.springframework.beans.factory.ObjectProvider<org.springframework.ai.mcp.SyncMcpToolCallbackProvider> mcpToolCallbacks,
                                     org.springframework.beans.factory.ObjectProvider<com.guonl.flow.ai.ToolSetProvider> toolSetProviders) {
        log.info("[AI] use SpringAiInvoker（直连大模型）");
        return new SpringAiInvoker(chatClientBuilder, aiProperties, flowTools, chatMemoryRepository, mcpToolCallbacks, toolSetProviders);
    }
}
