package com.guonl.flow.config;

import com.guonl.flow.ai.MockImageModel;
import com.guonl.flow.ai.MockModerationModel;
import com.guonl.flow.ai.MockTranscriptionModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 多模态能力装配：按 flow.ai.provider 切换图片生成/内容审核/语音转录模型实现。
 * <ul>
 *   <li>mock：本地模拟实现（确定性输出，无需外部API即可验证全链路）</li>
 *   <li>llm：复用 Spring AI 自动配置的 ImageModel/ModerationModel/TranscriptionModel
 *       （需在 spring.ai.model.* 中启用对应模型并配置端点），
 *       未装配时节点运行期给出明确报错而非启动失败</li>
 * </ul>
 */
@Slf4j
@Configuration
public class MultimodalConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "flow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
    public ImageModel mockImageModel() {
        log.info("[多模态] use MockImageModel（SVG占位图，mock模式）");
        return new MockImageModel();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "flow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
    public ModerationModel mockModerationModel() {
        log.info("[多模态] use MockModerationModel（关键词命中规则，mock模式）");
        return new MockModerationModel();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "flow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
    public TranscriptionModel mockTranscriptionModel() {
        log.info("[多模态] use MockTranscriptionModel（演示转录文本，mock模式）");
        return new MockTranscriptionModel();
    }
}
