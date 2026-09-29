package com.guonl.flow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI能力配置，前缀 {@code flow.ai}。
 * <p>provider=mock（默认）时返回模拟数据，便于无密钥环境体验完整交互；
 * provider=llm 时经 Spring AI OpenAI 兼容协议直连大模型，
 * 网关地址与密钥由 spring.ai.openai.* 标准配置提供。</p>
 */
@Data
@ConfigurationProperties(prefix = "flow.ai")
public class AiProperties {

    /** 服务模式：mock=本地模拟（缺省）| llm=直连大模型 */
    private String provider = "mock";

    /** 默认文本模型（节点未单独配置model时使用） */
    private String textModel = "gpt-4o-mini";

    /** 视觉模型（多模态节点未单独配置model时使用） */
    private String visionModel = "gpt-4o";

    /** 默认温度 */
    private double temperature = 0.3;

    /** 默认最大输出token（空则不限制） */
    private Integer maxOutputTokens;

    /** 单节点执行超时（秒） */
    private long nodeTimeoutSeconds = 180;

    /** 并行执行线程池大小 */
    private int executorPoolSize = 8;

    /** 图片下载超时（秒），图片URL需服务端代下载转base64传给模型 */
    private long imageDownloadTimeoutSeconds = 15;

    /** mock模式模拟延迟（毫秒），用于演示执行动效 */
    private long mockDelayMillis = 900;

    /** 结构化输出解析/校验失败后的纠错重试次数 */
    private int outputRetryAttempts = 2;

    /** 瞬时错误（限流/超时/连接抖动）自动重试次数 */
    private int invokeRetryAttempts = 2;

    /** 重试基础退避毫秒，按 2^(n-1) 指数递增，上限 8 秒 */
    private long retryBackoffMillis = 500;

    /** 主模型重试耗尽后的降级模型名，留空禁用 */
    private String fallbackModel = "";
}
