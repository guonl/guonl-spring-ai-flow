package com.guonl.flow.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置项。
 * <p>向量数据以 SimpleVectorStore JSON 形式落盘到 dataDir/{kbId}.json，元数据存 MySQL flow_knowledge 表。
 */
@Data
@ConfigurationProperties(prefix = "flow.knowledge")
public class KnowledgeProperties {

    /** 向量存储落盘目录 */
    private String dataDir = "data/kb";

    /** 文档切分的目标token数（TokenTextSplitter defaultChunkSize） */
    private int chunkSize = 800;

    /** 切分时重叠的最小字符数（minChunkSizeChars） */
    private int minChunkChars = 350;

    /** 检索默认返回条数 */
    private int defaultTopK = 4;

    /** 相似度阈值（低于该值的片段不返回） */
    private double similarityThreshold = 0.0;

    /** ETL异步嵌入的批大小（整批一次HTTP请求调用embedding服务，完成后刷新进度；OpenAI兼容服务通常限制单请求≤32条） */
    private int embedBatchSize = 16;

    /** 独立 embedding 服务配置：base-url 配置后知识库上传/检索改用该服务，不再复用 spring.ai.openai 的模型网关（适用于网关未开通 /v1/embeddings 的场景） */
    private Embedding embedding = new Embedding();

    @Data
    public static class Embedding {
        /** OpenAI 兼容 embedding 服务地址（如 https://dashscope.aliyuncs.com/compatible-mode/v1）；空=复用 spring.ai.openai 配置 */
        private String baseUrl;
        /** 该服务的 API Key；空=复用 spring.ai.openai 的 api-key */
        private String apiKey;
        /** embedding 模型名（如 text-embedding-v4 / bge-m3） */
        private String model;
        /** 向量维度（部分服务需显式指定；空=调用时自动探测并缓存） */
        private Integer dimensions;
    }
}
