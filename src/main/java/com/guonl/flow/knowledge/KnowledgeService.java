package com.guonl.flow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.db.entity.KnowledgeBaseDO;
import com.guonl.flow.db.entity.KnowledgeDocDO;
import com.guonl.flow.db.mapper.KnowledgeBaseMapper;
import com.guonl.flow.db.mapper.KnowledgeDocMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.BatchSimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库服务：元数据CRUD + 文档ETL管道（异步：切分→分批嵌入→向量入库→落盘）+ 相似度检索。
 * <p>ETL管道：上传后同步切分并落库（PROCESSING），后台线程分批调用embedding并向量入库，
 * 每批刷新 processed_chunks 进度；前端轮询文档列表展示进度条。
 */
@Slf4j
@Service
public class KnowledgeService {

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocMapper docMapper;
    private final VectorStoreManager storeManager;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** ETL后台线程池：切分后的分批嵌入较慢（远程embedding服务），异步执行避免HTTP请求阻塞/超时 */
    private final ExecutorService etlPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "kb-etl");
        t.setDaemon(true);
        return t;
    });

    public KnowledgeService(KnowledgeBaseMapper baseMapper, KnowledgeDocMapper docMapper,
                            VectorStoreManager storeManager, EmbeddingModel embeddingModel,
                            KnowledgeProperties properties) {
        this.baseMapper = baseMapper;
        this.docMapper = docMapper;
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @PreDestroy
    void shutdownPool() {
        etlPool.shutdown();
    }

    /**
     * 应用启动时恢复中断任务：上次进程退出时仍在 PROCESSING 的文档已无对应后台线程，
     * 置为 FAILED 让用户可感知并重新上传（删除该文档会按 chunkIds 清理已写入的半截切片）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedEtl() {
        int fixed = docMapper.failAllProcessing("服务重启导致处理中断，请删除后重新上传");
        if (fixed > 0) {
            log.warn("[知识库] 已将 {} 个中断的PROCESSING文档标记为FAILED", fixed);
        }
    }

    /** 新建知识库 */
    public KnowledgeBaseDO create(String name, String description) {
        KnowledgeBaseDO record = new KnowledgeBaseDO();
        record.setName(name);
        record.setDescription(description == null ? "" : description);
        baseMapper.insert(record);
        return baseMapper.findById(record.getId());
    }

    /** 知识库列表（含文档数/切片数） */
    public List<KnowledgeBaseDO> list() {
        return baseMapper.findAll();
    }

    /** 知识库详情 */
    public KnowledgeBaseDO get(Long id) {
        return baseMapper.findById(id);
    }

    /** 删除知识库：级联删除文档记录、向量数据、落盘文件（存在处理中文档时拒绝） */
    public void delete(Long id) {
        int processing = docMapper.countProcessingByKbId(id);
        if (processing > 0) {
            throw new IllegalArgumentException("知识库有 " + processing + " 个文档正在向量化处理中，请等待完成后再删除");
        }
        docMapper.deleteByKbId(id);
        storeManager.evict(id);
        File file = fileOf(id);
        if (file.exists()) {
            if (!file.delete()) {
                log.warn("[知识库] 落盘文件删除失败：{}", file.getAbsolutePath());
            }
        }
        baseMapper.deleteById(id);
        log.info("[知识库] 已删除 kbId={}", id);
    }

    /**
     * 上传文档并提交ETL管道：同步完成切分与记录落库（状态PROCESSING）后立即返回，
     * 分批嵌入→向量入库→落盘由后台线程异步执行，进度实时写入 processed_chunks。
     *
     * @param kbId    知识库ID
     * @param docName 文档名称（展示用）
     * @param text    文档纯文本内容
     * @return 文档记录（PROCESSING状态，含切片总数）
     */
    public KnowledgeDocDO uploadDoc(Long kbId, String docName, String text) {
        if (baseMapper.findById(kbId) == null) {
            throw new IllegalArgumentException("知识库不存在：" + kbId);
        }
        // 1. 同步切分（本地tokenizer，秒级）
        Document source = new Document(text, Map.of("docName", docName, "kbId", String.valueOf(kbId)));
        TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(properties.getChunkSize())
            .withMinChunkSizeChars(properties.getMinChunkChars())
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();
        List<Document> chunks = splitter.split(List.of(source));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档切分后无有效内容");
        }
        List<String> chunkIds = chunks.stream().map(Document::getId).toList();
        // 2. 记录落库（PROCESSING，chunk_ids预写全量，便于失败/中断时清理）
        KnowledgeDocDO doc = new KnowledgeDocDO();
        doc.setKbId(kbId);
        doc.setDocName(docName);
        doc.setChunkCount(0);
        doc.setTotalChunks(chunkIds.size());
        doc.setProcessedChunks(0);
        doc.setChunkIds(toJson(chunkIds));
        doc.setStatus("PROCESSING");
        docMapper.insert(doc);
        log.info("[知识库] 文档已受理 kbId={} docId={} 切片总数={}，开始后台向量化", kbId, doc.getId(), chunkIds.size());
        // 3. 异步执行分批嵌入
        etlPool.execute(() -> runEtl(doc, kbId, chunks));
        return doc;
    }

    /** 后台ETL：分批嵌入→入库→落盘→置READY；失败时清理半截切片并置FAILED */
    private void runEtl(KnowledgeDocDO doc, Long kbId, List<Document> chunks) {
        try {
            SimpleVectorStore store = storeManager.getStore(kbId);
            int batch = Math.max(1, properties.getEmbedBatchSize());
            int processed = 0;
            for (int i = 0; i < chunks.size(); i += batch) {
                List<Document> sub = chunks.subList(i, Math.min(i + batch, chunks.size()));
                // 整批一次HTTP请求完成嵌入（原 store.add 会逐条请求，每条一次 /v1/embeddings 调用，过慢）
                List<String> texts = sub.stream()
                    .map(d -> d.getFormattedContent(MetadataMode.EMBED))
                    .toList();
                List<float[]> vectors = embeddingModel.embed(texts);
                if (vectors.size() != sub.size()) {
                    throw new IllegalStateException(
                        "embedding服务返回向量数与请求不一致：" + vectors.size() + "/" + sub.size());
                }
                ((BatchSimpleVectorStore) store).addWithEmbeddings(sub, vectors);
                processed += sub.size();
                docMapper.updateProcessed(doc.getId(), processed);
                log.debug("[知识库] 向量化进度 docId={} {}/{}", doc.getId(), processed, chunks.size());
            }
            storeManager.persist(kbId);
            docMapper.finishEtl(doc.getId(), "READY", null);
            log.info("[知识库] 文档入库完成 kbId={} docId={} 切片数={}", kbId, doc.getId(), chunks.size());
        } catch (Exception e) {
            log.error("[知识库] 文档入库失败 kbId={} docId={}", kbId, doc.getId(), e);
            cleanupFailedEtl(kbId, doc.getId(), doc.getChunkIds(), e.getMessage());
        }
    }

    /** ETL失败收尾：从向量库移除可能已写入的部分切片并落盘，记录置FAILED（前端可见失败原因） */
    private void cleanupFailedEtl(Long kbId, Long docId, String chunkIdsJson, String reason) {
        try {
            List<String> chunkIds = fromJson(chunkIdsJson);
            if (!chunkIds.isEmpty()) {
                SimpleVectorStore store = storeManager.getStore(kbId);
                store.delete(chunkIds);
                storeManager.persist(kbId);
            }
        } catch (Exception ex) {
            log.warn("[知识库] 失败清理异常 docId={}", docId, ex);
        }
        docMapper.finishEtl(docId, "FAILED", reason == null ? "未知错误" : abbreviate(reason));
    }

    /** 文档列表 */
    public List<KnowledgeDocDO> listDocs(Long kbId) {
        return docMapper.findByKbId(kbId);
    }

    /** 删除文档：从向量库移除其所有切片并落盘（处理中拒绝，避免与后台ETL竞态产生孤儿切片） */
    public void deleteDoc(Long kbId, Long docId) {
        KnowledgeDocDO doc = docMapper.findById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new IllegalArgumentException("文档不存在：" + docId);
        }
        if ("PROCESSING".equals(doc.getStatus())) {
            throw new IllegalArgumentException("文档正在向量化处理中，请等待完成后再删除");
        }
        List<String> chunkIds = fromJson(doc.getChunkIds());
        if (!chunkIds.isEmpty()) {
            SimpleVectorStore store = storeManager.getStore(kbId);
            store.delete(chunkIds);
            storeManager.persist(kbId);
        }
        docMapper.deleteById(docId);
        log.info("[知识库] 文档已删除 kbId={} docId={} 切片数={}", kbId, docId, chunkIds.size());
    }

    /**
     * 相似度检索（C1调试 + C2检索增强共用）。
     */
    public List<RetrievedChunk> search(Long kbId, String query, Integer topK) {
        SimpleVectorStore store = storeManager.getStore(kbId);
        int k = topK == null || topK <= 0 ? properties.getDefaultTopK() : topK;
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
            .query(query)
            .topK(k)
            .similarityThreshold(properties.getSimilarityThreshold())
            .build());
        List<RetrievedChunk> result = new ArrayList<>();
        for (Document hit : hits) {
            Object docName = hit.getMetadata().get("docName");
            result.add(new RetrievedChunk(
                hit.getText(),
                hit.getScore() == null ? 0 : hit.getScore(),
                docName == null ? "" : String.valueOf(docName)));
        }
        return result;
    }

    private String toJson(List<String> chunkIds) {
        try {
            return objectMapper.writeValueAsString(chunkIds);
        } catch (IOException e) {
            throw new IllegalStateException("切片ID序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        } catch (IOException e) {
            log.warn("[知识库] 切片ID反序列化失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    private File fileOf(Long kbId) {
        return Path.of(properties.getDataDir(), kbId + ".json").toFile();
    }
}
