package com.guonl.flow.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.BatchSimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库向量存储管理器：按知识库ID提供 SimpleVectorStore 实例并负责JSON落盘/加载。
 * <p>落盘文件：{dataDir}/{kbId}.json；应用重启后首次访问时自动 load 恢复。
 */
@Slf4j
@Component
public class VectorStoreManager {

    private final KnowledgeProperties properties;
    private final EmbeddingModel embeddingModel;
    private final Map<Long, SimpleVectorStore> cache = new ConcurrentHashMap<>();

    public VectorStoreManager(KnowledgeProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 获取指定知识库的向量存储（不存在则创建，已有落盘文件则加载）。
     */
    public SimpleVectorStore getStore(long kbId) {
        return cache.computeIfAbsent(kbId, id -> {
            // BatchSimpleVectorStore：支持整批一次HTTP请求嵌入后直接写入（原版逐条请求过慢）
            SimpleVectorStore store = new BatchSimpleVectorStore(embeddingModel);
            File file = fileOf(id);
            if (file.exists()) {
                store.load(file);
                log.info("[知识库] 已从 {} 恢复向量存储（kbId={}）", file.getAbsolutePath(), id);
            }
            return store;
        });
    }

    /**
     * 持久化指定知识库的向量数据到磁盘。
     */
    public void persist(long kbId) {
        SimpleVectorStore store = cache.get(kbId);
        if (store == null) {
            return;
        }
        try {
            File file = fileOf(kbId);
            Files.createDirectories(file.getParentFile().toPath());
            store.save(file);
            log.info("[知识库] 向量数据已落盘 {}（kbId={}）", file.getAbsolutePath(), kbId);
        } catch (IOException e) {
            throw new IllegalStateException("向量数据落盘失败（kbId=" + kbId + "）", e);
        }
    }

    /**
     * 丢弃内存缓存（重建文档后调用，下次访问会从磁盘重新加载）。
     */
    public void evict(long kbId) {
        cache.remove(kbId);
    }

    private File fileOf(long kbId) {
        return Path.of(properties.getDataDir(), kbId + ".json").toFile();
    }
}
