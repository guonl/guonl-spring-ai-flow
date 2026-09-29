package org.springframework.ai.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * SimpleVectorStore 扩展：支持「预计算向量」批量写入，绕过逐条嵌入。
 *
 * <p>原版 {@link SimpleVectorStore#doAdd(List)} 对每个 Document 单独调用一次
 * {@code EmbeddingModel.embed(Document)}（每次一条 HTTP 请求），上传大文档时延迟逐条叠加。
 * 本类允许调用方对一批切片只调用一次 embedding 服务（OpenAI 兼容 /v1/embeddings
 * 的 input 支持数组，一次请求即可批量嵌入），再通过
 * {@link #addWithEmbeddings(List, List)} 直接写入内部存储。
 *
 * <p>注意：{@code SimpleVectorStoreContent} 与父类 {@code store} 字段的访问要求
 * 本类必须位于 {@code org.springframework.ai.vectorstore} 包下（split package，
 * classpath 下合法）。
 */
public class BatchSimpleVectorStore extends SimpleVectorStore {

    public BatchSimpleVectorStore(EmbeddingModel embeddingModel) {
        super(SimpleVectorStore.builder(embeddingModel));
    }

    /**
     * 直接写入已完成嵌入的文档（不再调用 embeddingModel），写入语义与 doAdd 一致：
     * 按 Document id 覆盖存储 text/metadata/向量。
     *
     * @param documents  已切分的文档切片
     * @param embeddings 与 documents 一一对应的嵌入向量
     */
    public void addWithEmbeddings(List<Document> documents, List<float[]> embeddings) {
        Assert.notNull(documents, "Documents list cannot be null");
        Assert.notNull(embeddings, "Embeddings list cannot be null");
        Assert.isTrue(documents.size() == embeddings.size(), "documents 与 embeddings 数量不一致");
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            SimpleVectorStoreContent content = new SimpleVectorStoreContent(
                doc.getId(),
                Objects.requireNonNullElse(doc.getText(), ""),
                doc.getMetadata(),
                embeddings.get(i));
            this.store.put(doc.getId(), content);
        }
    }
}
