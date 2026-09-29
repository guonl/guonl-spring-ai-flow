package com.guonl.flow.knowledge;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 知识库装配：按 flow.ai.provider 切换嵌入模型实现。
 * <ul>
 *   <li>独立配置（flow.knowledge.embedding.base-url）：知识库使用专用 embedding 服务，优先级最高（解决模型网关未开通 /v1/embeddings 的场景）</li>
 *   <li>mock：本地确定性嵌入（MockEmbeddingModel），无需外部API即可验证检索链路</li>
 *   <li>llm：复用 spring-ai openai 自动配置的 EmbeddingModel（走模型网关 /v1/embeddings）</li>
 * </ul>
 * <p>注意：切换 embedding 实现后向量空间随之变化，已上传文档需删除后重新上传。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {

    /**
     * 独立 embedding 模型（配置了 flow.knowledge.embedding.base-url 时生效，最高优先级）。
     * <p>服务地址/密钥/模型名均独立于 spring.ai.openai（其配置与 chat 共享，无法单独指向 embedding 服务），
     * 适用于模型网关未开通 embedding 模型、需接入第三方或自建 OpenAI 兼容 embedding 服务的场景。
     */
    @Bean
    @Primary
    @Conditional(StandaloneEmbeddingCondition.class)
    public EmbeddingModel standaloneEmbeddingModel(KnowledgeProperties properties) {
        KnowledgeProperties.Embedding cfg = properties.getEmbedding();
        String apiKey = (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) ? "unused" : cfg.getApiKey();
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder().model(cfg.getModel());
        if (cfg.getDimensions() != null) {
            options.dimensions(cfg.getDimensions());
        }
        log.info("[知识库] use 独立Embedding服务: base-url={}, model={}, dimensions={}",
                cfg.getBaseUrl(), cfg.getModel(), cfg.getDimensions() != null ? cfg.getDimensions() : "自动探测");
        // Spring AI 2.x 基于 openai-java SDK（无 OpenAiApi 抽象），经 ClientOptions 构造原生客户端；
        // httpClient 为 ClientOptions 必填项，复用 spring-ai-openai 内置的 okhttp 适配实现
        OpenAIClient client = new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .build());
        return OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .metadataMode(MetadataMode.EMBED)
                .options(options.build())
                .build();
    }

    /**
     * 嵌入模型（mock模式）：本地hash嵌入，覆盖 spring-ai openai 自动配置的 bean（@Primary）。
     * <p>条件：provider=mock 且未配置独立 embedding 服务——避免与独立模型双 @Primary 冲突。
     */
    @Bean
    @Primary
    @Conditional(MockEmbeddingCondition.class)
    public EmbeddingModel mockEmbeddingModel() {
        log.info("[知识库] use MockEmbeddingModel（本地确定性嵌入，维度={}）", MockEmbeddingModel.DIMENSIONS);
        return new MockEmbeddingModel();
    }

    /** provider=mock 且未配置独立 embedding 服务 */
    static class MockEmbeddingCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean mockProvider = !"llm".equals(env.getProperty("flow.ai.provider", "mock"));
            String standalone = env.getProperty("flow.knowledge.embedding.base-url", "");
            boolean mockProviderHit = mockProvider && (standalone == null || standalone.isBlank());
            return new ConditionOutcome(mockProviderHit,
                    mockProviderHit ? "provider=mock 且未配置独立embedding服务" : "provider=llm 或已配置独立embedding服务");
        }
    }

    /** flow.knowledge.embedding.base-url 已配置（非空） */
    static class StandaloneEmbeddingCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String baseUrl = context.getEnvironment().getProperty("flow.knowledge.embedding.base-url", "");
            boolean hit = baseUrl != null && !baseUrl.isBlank();
            return new ConditionOutcome(hit, hit ? "flow.knowledge.embedding.base-url=" + baseUrl : "未配置 flow.knowledge.embedding.base-url");
        }
    }
}
