package com.guonl.flow.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingModel;

import java.text.Normalizer;
import java.util.List;

/**
 * 本地确定性嵌入模型（mock模式）。
 * <p>算法：文本按2字符滑窗gram散列到固定维度桶并做L2归一化。
 * 相同文本产生相同向量（确定性），语义相近文本桶部分重叠（余弦相似度可区分），
 * 从而在无外部API的情况下可验证"切分→嵌入→检索"完整链路。
 */
public class MockEmbeddingModel implements EmbeddingModel {

    /** 嵌入向量维度 */
    public static final int DIMENSIONS = 256;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> embeddings = new java.util.ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(embedText(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    private float[] embedText(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        // 归一化文本：全角转半角、小写、NFC
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase();
        // 2字符滑窗gram散列累加
        for (int i = 0; i < normalized.length() - 1; i++) {
            int gram = normalized.charAt(i) * 31 + normalized.charAt(i + 1);
            int idx = Math.floorMod(gram, DIMENSIONS);
            vector[idx] += 1f;
        }
        // 处理单字符文本
        if (normalized.length() == 1) {
            int idx = Math.floorMod(normalized.charAt(0), DIMENSIONS);
            vector[idx] += 1f;
        }
        // L2归一化
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        if (sum > 0) {
            float norm = (float) Math.sqrt(sum);
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
